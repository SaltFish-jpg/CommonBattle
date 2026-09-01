package com.commonbattle.actor.agent.lifecycle;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.AgentRoute;
import com.commonbattle.cluster.ServiceId;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本服务 Agent 生命周期管理器。
 * 它负责激活真实 owner，并在钝化或迁移期间让本地路由停止继续向旧邮箱投递新业务。
 */
public final class AgentLifecycleManager {
    private final ServiceId localServiceId;
    private final ActorSystem actors;
    private final AgentDirectory directory;
    private final Clock clock;
    private final Map<AgentIdentity, AgentLifecycleRecord> records = new ConcurrentHashMap<>();

    public AgentLifecycleManager(ServiceId localServiceId, ActorSystem actors, AgentDirectory directory, Clock clock) {
        this.localServiceId = Objects.requireNonNull(localServiceId, "localServiceId");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        if (!directory.claim(identity, location)) {
            throw new AgentAlreadyOwnedException(identity);
        }
        records.put(identity, new AgentLifecycleRecord(identity, location, AgentLifecycleState.ACTIVE, clock.instant()));
        actors.send(actorRef, onActivate::run);
        return location;
    }

    public boolean passivate(AgentIdentity identity, AgentLifecycleAction onPassivate) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(onPassivate, "onPassivate");
        AgentLifecycleRecord active = transitionFromActive(identity, AgentLifecycleState.PASSIVATING);
        actors.send(active.location().actorRef(), context -> {
            // 钝化边界：保存和释放在旧 owner 邮箱内串行完成，完成后再从目录解绑。
            onPassivate.run(context);
            directory.unbind(identity, active.location());
            records.put(identity, new AgentLifecycleRecord(identity, active.location(),
                    AgentLifecycleState.PASSIVATED, clock.instant()));
        });
        return true;
    }

    public boolean migrate(AgentIdentity identity, AgentLocation target, AgentLifecycleAction beforeMove) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(beforeMove, "beforeMove");
        AgentLifecycleRecord active = transitionFromActive(identity, AgentLifecycleState.MIGRATING);
        actors.send(active.location().actorRef(), context -> {
            // 迁移边界：迁移打包在旧 owner 邮箱内完成，目录 CAS 成功后新 owner 才可被路由发现。
            beforeMove.run(context);
            if (directory.move(identity, active.location(), target)) {
                records.put(identity, new AgentLifecycleRecord(identity, target, AgentLifecycleState.MIGRATED,
                        clock.instant()));
                return;
            }
            records.put(identity, new AgentLifecycleRecord(identity, active.location(), AgentLifecycleState.CLOSED,
                    clock.instant()));
        });
        return true;
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
        return records.getOrDefault(identity, inactiveRecord(identity, location)).state() == AgentLifecycleState.ACTIVE
                ? AgentRoute.local(location)
                : AgentRoute.missing();
    }

    public Optional<AgentLifecycleRecord> record(AgentIdentity identity) {
        return Optional.ofNullable(records.get(identity));
    }

    public Map<AgentIdentity, AgentLifecycleRecord> records() {
        return Map.copyOf(records);
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
}
