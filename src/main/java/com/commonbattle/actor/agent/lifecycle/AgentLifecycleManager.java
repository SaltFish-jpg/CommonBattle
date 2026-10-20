package com.commonbattle.actor.agent.lifecycle;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.AgentDirectoryLease;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLeaseSupervisor;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.AgentRoute;
import com.commonbattle.cluster.ServiceId;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本服务 Agent 生命周期管理器。
 * 它负责激活真实 owner，并在钝化或迁移期间让本地路由停止继续向旧邮箱投递新业务。
 */
public final class AgentLifecycleManager {
    private final ServiceId localServiceId;
    private final ActorSystem actors;
    private final AgentDirectory directory;
    private final AgentLeaseSupervisor leaseSupervisor;
    private final Clock clock;
    private final Map<AgentIdentity, AgentLifecycleRecord> records = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<AgentLifecycleInvalidationListener> invalidationListeners =
            new CopyOnWriteArrayList<>();

    public AgentLifecycleManager(ServiceId localServiceId, ActorSystem actors, AgentDirectory directory, Clock clock) {
        this(localServiceId, actors, directory, clock, null);
    }

    public AgentLifecycleManager(
            ServiceId localServiceId,
            ActorSystem actors,
            AgentDirectory directory,
            Clock clock,
            AgentLeaseSupervisor leaseSupervisor
    ) {
        this.localServiceId = Objects.requireNonNull(localServiceId, "localServiceId");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseSupervisor = leaseSupervisor;
    }

    public AgentLocation activate(AgentIdentity identity, String actorId) {
        return activate(identity, actorId, AgentLifecycleAction.none());
    }

    public AgentLocation activate(AgentIdentity identity, String actorId, AgentLifecycleAction onActivate) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(onActivate, "onActivate");
        ActorRef actorRef = actors.actor(actorId);
        AgentLocation location = new AgentLocation(localServiceId, actorRef);
        if (!claim(identity, location)) {
            throw new AgentAlreadyOwnedException(identity);
        }
        records.put(identity, new AgentLifecycleRecord(identity, location, AgentLifecycleState.ACTIVE, clock.instant()));
        actors.send(actorRef, onActivate::run);
        return location;
    }

    public AgentLocation acceptMigrated(AgentIdentity identity, String actorId, AgentLifecycleAction onActivate) {
        return acceptMigrated(identity, actorId, onActivate, AgentLifecycleResultCallback.ignore());
    }

    public AgentLocation acceptMigrated(
            AgentIdentity identity,
            String actorId,
            AgentLifecycleAction onActivate,
            AgentLifecycleResultCallback callback
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(onActivate, "onActivate");
        Objects.requireNonNull(callback, "callback");
        ActorRef actorRef = actors.actor(actorId);
        AgentLocation location = new AgentLocation(localServiceId, actorRef);
        if (!adopt(identity, location)) {
            throw new AgentMigrationTargetMismatchException(identity, location);
        }
        actors.send(actorRef, context -> {
            try {
                onActivate.run(context);
                records.put(identity, new AgentLifecycleRecord(identity, location, AgentLifecycleState.ACTIVE,
                        clock.instant()));
                callback.succeeded(location);
            } catch (Throwable error) {
                records.remove(identity);
                forgetLease(identity);
                actors.retireCurrent(actorRef);
                callback.failed(error);
                if (error instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (error instanceof Error fatal) {
                    throw fatal;
                }
                throw new IllegalStateException(error);
            }
        });
        return location;
    }

    public boolean passivate(AgentIdentity identity, AgentLifecycleAction onPassivate) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(onPassivate, "onPassivate");
        AgentLifecycleRecord active = transitionFromActive(identity, AgentLifecycleState.PASSIVATING);
        actors.send(active.location().actorRef(), context -> {
            try {
                // 钝化边界：保存和释放在旧 owner 邮箱内串行完成，完成后再从目录解绑。
                onPassivate.run(context);
                actors.retireCurrent(active.location().actorRef());
                release(identity, active.location());
                AgentLifecycleRecord passivated = new AgentLifecycleRecord(identity, active.location(),
                        AgentLifecycleState.PASSIVATED, clock.instant());
                records.put(identity, passivated);
                notifyInvalidated(passivated);
            } catch (RuntimeException e) {
                records.put(identity, new AgentLifecycleRecord(identity, active.location(),
                        AgentLifecycleState.ACTIVE, clock.instant()));
                throw e;
            }
        });
        return true;
    }

    public boolean migrate(AgentIdentity identity, AgentLocation target, AgentLifecycleAction beforeMove) {
        return migrate(identity, target, beforeMove, AgentMigrationCompletionListener.ignore());
    }

    public boolean migrate(
            AgentIdentity identity,
            AgentLocation target,
            AgentLifecycleAction beforeMove,
            AgentMigrationCompletionListener listener
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(beforeMove, "beforeMove");
        Objects.requireNonNull(listener, "listener");
        AgentLifecycleRecord active = transitionFromActive(identity, AgentLifecycleState.MIGRATING);
        actors.send(active.location().actorRef(), context -> {
            AgentMigrationCompletion completion;
            try {
                // 迁移边界：迁移打包在旧 owner 邮箱内完成，目录 CAS 成功后新 owner 才可被路由发现。
                beforeMove.run(context);
                if (move(identity, active.location(), target)) {
                    AgentLifecycleRecord migrated = new AgentLifecycleRecord(
                            identity, target, AgentLifecycleState.MIGRATED, clock.instant()
                    );
                    records.put(identity, migrated);
                    completion = new AgentMigrationCompletion(identity, active.location(), target, true, null);
                } else {
                    AgentLifecycleState failedState = directory.locate(identity)
                            .filter(active.location()::equals)
                            .map(ignored -> AgentLifecycleState.ACTIVE)
                            .orElse(AgentLifecycleState.CLOSED);
                    AgentLifecycleRecord failed = new AgentLifecycleRecord(
                            identity, active.location(), failedState, clock.instant()
                    );
                    records.put(identity, failed);
                    if (failedState == AgentLifecycleState.CLOSED) {
                        notifyInvalidated(failed);
                    }
                    completion = new AgentMigrationCompletion(identity, active.location(), target, false, null);
                }
            } catch (RuntimeException e) {
                records.put(identity, new AgentLifecycleRecord(identity, active.location(),
                        AgentLifecycleState.ACTIVE, clock.instant()));
                listener.completed(new AgentMigrationCompletion(identity, active.location(), target, false, e));
                throw e;
            }
            listener.completed(completion);
        });
        return true;
    }

    public boolean resumeAfterMigrationRollback(AgentIdentity identity, AgentLocation sourceLocation) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(sourceLocation, "sourceLocation");
        if (!sourceLocation.isLocal(localServiceId)) {
            return false;
        }
        if (directory.locate(identity).filter(sourceLocation::equals).isEmpty()) {
            return false;
        }
        records.put(identity, new AgentLifecycleRecord(identity, sourceLocation, AgentLifecycleState.ACTIVE,
                clock.instant()));
        return true;
    }

    public boolean rollbackMigration(
            AgentIdentity identity,
            AgentLocation expectedCurrent,
            AgentLocation sourceLocation
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(expectedCurrent, "expectedCurrent");
        Objects.requireNonNull(sourceLocation, "sourceLocation");
        boolean restored = leaseSupervisor == null
                ? directory.move(identity, expectedCurrent, sourceLocation)
                : leaseSupervisor.reclaim(identity, expectedCurrent, sourceLocation).isPresent();
        return restored && resumeAfterMigrationRollback(identity, sourceLocation);
    }

    public boolean recoverMigrationMove(
            AgentIdentity identity,
            AgentLocation expectedCurrent,
            AgentLocation target
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(expectedCurrent, "expectedCurrent");
        Objects.requireNonNull(target, "target");
        return leaseSupervisor == null
                ? directory.move(identity, expectedCurrent, target)
                : leaseSupervisor.handoffCurrent(identity, expectedCurrent, target).isPresent();
    }

    /**
     * 租约监督线程只调用此入口；实际 fencing 作为 SYSTEM 消息回到对应 Actor 邮箱执行。
     */
    public void handleLeaseLost(AgentDirectoryLease lease) {
        Objects.requireNonNull(lease, "lease");
        AgentLifecycleRecord current = records.get(lease.identity());
        if (current == null || !current.location().equals(lease.location())) {
            return;
        }
        ActorTask fenceTask = ActorTask.categorized(ActorTaskCategory.SYSTEM, ignored ->
                fenceLostLease(lease.identity(), lease.location())
        );
        if (!actors.trySend(lease.location().actorRef(), fenceTask)) {
            fenceLostLeaseOutsideMailbox(lease.identity(), lease.location());
        }
    }

    /**
     * 在当前 Agent 邮箱内隔离已经失去持久化 fencing token 的 owner。
     * 目录解绑带 expected location 条件，不会误删已经迁移到其他服务的新 owner。
     */
    public boolean fenceCurrent(AgentIdentity identity, ActorRef actorRef) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(actorRef, "actorRef");
        com.commonbattle.actor.ActorContext.requireCurrent(actorRef);
        AgentLifecycleRecord current = records.get(identity);
        if (current == null || !current.location().actorRef().equals(actorRef)) {
            return false;
        }
        AgentLifecycleRecord fenced = new AgentLifecycleRecord(
                identity, current.location(), AgentLifecycleState.FENCED, clock.instant()
        );
        if (!records.replace(identity, current, fenced)) {
            return false;
        }
        release(identity, current.location());
        actors.retireCurrent(actorRef);
        notifyInvalidated(fenced);
        return true;
    }

    /**
     * 激活阶段恢复失败时，按预期位置释放本地 owner 和邮箱。
     */
    public boolean closeFailedActivation(AgentIdentity identity, ActorRef actorRef) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(actorRef, "actorRef");
        AgentLifecycleRecord current = records.get(identity);
        if (current == null || !current.location().actorRef().equals(actorRef)) {
            return false;
        }
        if (!records.remove(identity, current)) {
            return false;
        }
        release(identity, current.location());
        actors.retire(actorRef);
        notifyInvalidated(new AgentLifecycleRecord(
                identity, current.location(), AgentLifecycleState.CLOSED, clock.instant()
        ));
        return true;
    }

    /**
     * 注册进程内引用失效监听器。监听器必须快速返回，不应执行阻塞 I/O。
     */
    public AgentLifecycleManager addInvalidationListener(AgentLifecycleInvalidationListener listener) {
        invalidationListeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public AgentRoute resolve(AgentIdentity identity) {
        Optional<AgentLocation> located = directory.locate(identity);
        if (located.isEmpty()) {
            return AgentRoute.missing();
        }
        AgentLocation location = located.orElseThrow();
        if (!location.isLocal(localServiceId)) {
            return AgentRoute.remote(location);
        }
        AgentLifecycleState state = records.getOrDefault(identity, inactiveRecord(identity, location)).state();
        if (state == AgentLifecycleState.ACTIVE) {
            return AgentRoute.local(location);
        }
        if (state == AgentLifecycleState.MIGRATING) {
            return AgentRoute.missing("agent_migrating");
        }
        if (state == AgentLifecycleState.PASSIVATING) {
            return AgentRoute.missing("agent_passivating");
        }
        return AgentRoute.missing("agent_inactive");
    }

    public Optional<AgentLifecycleRecord> record(AgentIdentity identity) {
        return Optional.ofNullable(records.get(identity));
    }

    public Map<AgentIdentity, AgentLifecycleRecord> records() {
        return Map.copyOf(records);
    }

    public Optional<AgentDirectoryLease> lease(AgentIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return leaseSupervisor == null ? Optional.empty() : leaseSupervisor.lease(identity);
    }

    private AgentLifecycleRecord transitionFromActive(AgentIdentity identity, AgentLifecycleState nextState) {
        AgentLifecycleRecord current = records.get(identity);
        if (current == null || current.state() != AgentLifecycleState.ACTIVE) {
            throw new AgentNotActiveException(identity);
        }
        AgentLifecycleRecord next = new AgentLifecycleRecord(identity, current.location(), nextState, clock.instant());
        boolean changed = records.replace(identity, current, next);
        if (!changed) {
            throw new AgentNotActiveException(identity);
        }
        return current;
    }

    private AgentLifecycleRecord inactiveRecord(AgentIdentity identity, AgentLocation location) {
        return new AgentLifecycleRecord(identity, location, AgentLifecycleState.CLOSED, clock.instant());
    }

    private boolean claim(AgentIdentity identity, AgentLocation location) {
        return leaseSupervisor == null
                ? directory.claim(identity, location)
                : leaseSupervisor.acquire(identity, location).isPresent();
    }

    private boolean adopt(AgentIdentity identity, AgentLocation location) {
        return leaseSupervisor == null
                ? directory.locate(identity).filter(location::equals).isPresent()
                : leaseSupervisor.adopt(identity, location).isPresent();
    }

    private boolean move(AgentIdentity identity, AgentLocation source, AgentLocation target) {
        return leaseSupervisor == null
                ? directory.move(identity, source, target)
                : leaseSupervisor.handoff(identity, target).isPresent();
    }

    private void release(AgentIdentity identity, AgentLocation location) {
        if (leaseSupervisor == null) {
            directory.unbind(identity, location);
        } else {
            leaseSupervisor.release(identity);
        }
    }

    private void forgetLease(AgentIdentity identity) {
        if (leaseSupervisor != null) {
            leaseSupervisor.forget(identity);
        }
    }

    private void fenceLostLease(AgentIdentity identity, AgentLocation location) {
        AgentLifecycleRecord current = records.get(identity);
        if (current == null || !current.location().equals(location)) {
            return;
        }
        AgentLifecycleRecord fenced = new AgentLifecycleRecord(
                identity, location, AgentLifecycleState.FENCED, clock.instant()
        );
        if (!records.replace(identity, current, fenced)) {
            return;
        }
        actors.retireCurrent(location.actorRef());
        notifyInvalidated(fenced);
    }

    private void fenceLostLeaseOutsideMailbox(AgentIdentity identity, AgentLocation location) {
        AgentLifecycleRecord current = records.get(identity);
        if (current == null || !current.location().equals(location)) {
            return;
        }
        AgentLifecycleRecord fenced = new AgentLifecycleRecord(
                identity, location, AgentLifecycleState.FENCED, clock.instant()
        );
        if (!records.replace(identity, current, fenced)) {
            return;
        }
        actors.retire(location.actorRef());
        notifyInvalidated(fenced);
    }

    private void notifyInvalidated(AgentLifecycleRecord record) {
        for (AgentLifecycleInvalidationListener listener : invalidationListeners) {
            try {
                listener.invalidated(record);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
