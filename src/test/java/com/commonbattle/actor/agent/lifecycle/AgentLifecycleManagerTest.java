package com.commonbattle.actor.agent.lifecycle;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.AgentDirectoryLease;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLeaseSupervisor;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.AgentRouteType;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.SerializedAgentDirectory;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLifecycleManagerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void activateClaimsOwnerAndRoutesLocalTask() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
        LifecycleAwareAgentRouter router = new LifecycleAwareAgentRouter(
                lifecycles,
                new DefaultAgentMessagePort(actors, new NoopRpcGateway())
        );
        AgentIdentity player = AgentIdentity.player(10001L);
        AtomicInteger runs = new AtomicInteger();

        lifecycles.activate(player, "player-10001");
        executor.runNext();
        assertEquals(AgentLifecycleState.ACTIVE, lifecycles.record(player).orElseThrow().state());

        assertEquals(AgentRouteType.LOCAL, router.tellLocalOrRoute(player, ignored -> runs.incrementAndGet()).type());
        executor.runNext();

        assertEquals(1, runs.get());
    }

    @Test
    void passivatingAgentStopsAcceptingNewLocalRouteAndThenUnbinds() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
        LifecycleAwareAgentRouter router = new LifecycleAwareAgentRouter(
                lifecycles,
                new DefaultAgentMessagePort(actors, new NoopRpcGateway())
        );
        AgentIdentity player = AgentIdentity.player(10001L);
        AtomicInteger saved = new AtomicInteger();
        lifecycles.activate(player, "player-10001");
        executor.runNext();

        lifecycles.passivate(player, ignored -> saved.incrementAndGet());

        assertEquals(AgentRouteType.MISSING, router.tellLocalOrRoute(player, ignored -> {
            throw new AssertionError("passivating agent must not accept new business");
        }).type());
        executor.runNext();

        assertEquals(1, saved.get());
        assertEquals(AgentLifecycleState.PASSIVATED, lifecycles.record(player).orElseThrow().state());
        assertTrue(directory.locate(player).isEmpty());
    }

    @Test
    void migrateStopsOldLocalRouteAndMovesDirectoryAfterMailboxAction() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        ServiceId remote = ServiceId.of(ServiceKind.GAME, "r1", "game-2");
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
        LifecycleAwareAgentRouter router = new LifecycleAwareAgentRouter(
                lifecycles,
                new DefaultAgentMessagePort(actors, new NoopRpcGateway())
        );
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation remoteLocation = new AgentLocation(remote, new ActorRef("player-10001"));
        AtomicInteger packed = new AtomicInteger();
        AtomicInteger invalidations = new AtomicInteger();
        lifecycles.addInvalidationListener(ignored -> invalidations.incrementAndGet());
        lifecycles.activate(player, "player-10001");
        executor.runNext();

        lifecycles.migrate(player, remoteLocation, ignored -> packed.incrementAndGet());

        assertEquals(AgentRouteType.MISSING, router.resolve(player).type());
        assertEquals(local, directory.locate(player).orElseThrow().serviceId());

        executor.runNext();

        assertEquals(1, packed.get());
        assertEquals(remote, directory.locate(player).orElseThrow().serviceId());
        assertEquals(AgentLifecycleState.MIGRATED, lifecycles.record(player).orElseThrow().state());
        assertEquals(AgentRouteType.REMOTE, router.resolve(player).type());
        assertEquals(0, invalidations.get());
    }

    @Test
    void migratedAgentMustBeAcceptedByAssignedTargetOwner() {
        RecordingExecutor oldExecutor = new RecordingExecutor();
        RecordingExecutor newExecutor = new RecordingExecutor();
        ActorSystem oldActors = new ActorSystem(oldExecutor, 64);
        ActorSystem newActors = new ActorSystem(newExecutor, 64);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId oldService = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        ServiceId newService = ServiceId.of(ServiceKind.GAME, "r1", "game-2");
        AgentLifecycleManager oldOwner = new AgentLifecycleManager(oldService, oldActors, directory, CLOCK);
        AgentLifecycleManager newOwner = new AgentLifecycleManager(newService, newActors, directory, CLOCK);
        LifecycleAwareAgentRouter newRouter = new LifecycleAwareAgentRouter(
                newOwner,
                new DefaultAgentMessagePort(newActors, new NoopRpcGateway())
        );
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation target = new AgentLocation(newService, new ActorRef("player-10001"));
        AtomicInteger packed = new AtomicInteger();
        AtomicInteger restored = new AtomicInteger();
        oldOwner.activate(player, "player-10001");
        oldExecutor.runNext();

        oldOwner.migrate(player, target, ignored -> packed.incrementAndGet());
        assertThrows(AgentMigrationTargetMismatchException.class,
                () -> newOwner.acceptMigrated(player, "player-10001", ignored -> restored.incrementAndGet()));
        oldExecutor.runNext();
        newOwner.acceptMigrated(player, "player-10001", ignored -> restored.incrementAndGet());
        newExecutor.runNext();

        assertEquals(1, packed.get());
        assertEquals(1, restored.get());
        assertEquals(AgentLifecycleState.ACTIVE, newOwner.record(player).orElseThrow().state());
        assertEquals(AgentRouteType.LOCAL, newRouter.resolve(player).type());
    }

    @Test
    void migratedAgentBecomesActiveOnlyAfterRestoreActionSucceeds() {
        RecordingExecutor oldExecutor = new RecordingExecutor();
        RecordingExecutor newExecutor = new RecordingExecutor();
        ActorSystem oldActors = new ActorSystem(oldExecutor, 64);
        ActorSystem newActors = new ActorSystem(newExecutor, 64);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId oldService = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        ServiceId newService = ServiceId.of(ServiceKind.GAME, "r1", "game-2");
        AgentLifecycleManager oldOwner = new AgentLifecycleManager(oldService, oldActors, directory, CLOCK);
        AgentLifecycleManager newOwner = new AgentLifecycleManager(newService, newActors, directory, CLOCK);
        LifecycleAwareAgentRouter newRouter = new LifecycleAwareAgentRouter(
                newOwner,
                new DefaultAgentMessagePort(newActors, new NoopRpcGateway())
        );
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation target = new AgentLocation(newService, new ActorRef("player-10001"));
        AtomicInteger failures = new AtomicInteger();
        oldOwner.activate(player, "player-10001");
        oldExecutor.runNext();
        oldOwner.migrate(player, target, ignored -> {
        });
        oldExecutor.runNext();

        newOwner.acceptMigrated(player, "player-10001", ignored -> {
            throw new IllegalStateException("restore failed");
        }, new AgentLifecycleResultCallback() {
            @Override
            public void succeeded(AgentLocation location) {
                throw new AssertionError("restore should fail");
            }

            @Override
            public void failed(Throwable error) {
                failures.incrementAndGet();
            }
        });
        newExecutor.runNext();

        assertEquals(1, failures.get());
        assertTrue(newOwner.record(player).isEmpty());
        assertEquals(AgentRouteType.MISSING, newRouter.resolve(player).type());
    }

    @Test
    void migrationFailureRestoresActiveWhenDirectoryStillPointsToOldOwner() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        FailingMoveDirectory directory = new FailingMoveDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        ServiceId remote = ServiceId.of(ServiceKind.GAME, "r1", "game-2");
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
        LifecycleAwareAgentRouter router = new LifecycleAwareAgentRouter(
                lifecycles,
                new DefaultAgentMessagePort(actors, new NoopRpcGateway())
        );
        AgentIdentity player = AgentIdentity.player(10001L);
        AtomicInteger routed = new AtomicInteger();
        lifecycles.activate(player, "player-10001");
        executor.runNext();

        lifecycles.migrate(player, new AgentLocation(remote, new ActorRef("player-10001")), ignored -> {
        });
        executor.runNext();

        assertEquals(AgentLifecycleState.ACTIVE, lifecycles.record(player).orElseThrow().state());
        assertEquals(AgentRouteType.LOCAL, router.tellLocalOrRoute(player, ignored -> routed.incrementAndGet()).type());
        executor.runNext();
        assertEquals(1, routed.get());
    }

    @Test
    void activateFailsWhenDirectoryAlreadyHasAnotherOwner() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        ServiceId remote = ServiceId.of(ServiceKind.GAME, "r1", "game-2");
        AgentIdentity player = AgentIdentity.player(10001L);
        directory.claim(player, new AgentLocation(remote, new ActorRef("player-10001")));
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);

        assertThrows(AgentAlreadyOwnedException.class, () -> lifecycles.activate(player, "player-10001"));
    }

    @Test
    void fencingOldMailboxDoesNotUnbindAlreadyMovedTargetOwner() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        ServiceId remote = ServiceId.of(ServiceKind.GAME, "r1", "game-2");
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation source = lifecycles.activate(player, "player-10001");
        executor.runNext();
        AgentLocation target = new AgentLocation(remote, new ActorRef("player-10001"));
        assertTrue(directory.move(player, source, target));

        actors.send(source.actorRef(), ignored -> lifecycles.fenceCurrent(player, source.actorRef()));
        executor.runNext();

        assertEquals(target, directory.locate(player).orElseThrow());
        assertEquals(AgentLifecycleState.FENCED, lifecycles.record(player).orElseThrow().state());
        assertEquals(0, actors.stats().registeredMailboxes());
    }

    @Test
    void failedActivationCleanupReleasesClaimedOwnerAndMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"), actors, directory, CLOCK
        );
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation location = lifecycles.activate(player, "player-10001");

        assertTrue(lifecycles.closeFailedActivation(player, location.actorRef()));
        executor.runNext();

        assertTrue(directory.locate(player).isEmpty());
        assertTrue(lifecycles.record(player).isEmpty());
        assertEquals(0, actors.stats().registeredMailboxes());
    }

    @Test
    void leasedLifecycleMigratesByHandoffAndAdoption() {
        RecordingExecutor sourceExecutor = new RecordingExecutor();
        RecordingExecutor targetExecutor = new RecordingExecutor();
        ActorSystem sourceActors = new ActorSystem(sourceExecutor, 64);
        ActorSystem targetActors = new ActorSystem(targetExecutor, 64);
        SerializedAgentDirectory directory = new SerializedAgentDirectory(
                new InMemoryAtomicBytesStore(), CLOCK
        );
        ServiceId sourceService = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        ServiceId targetService = ServiceId.of(ServiceKind.GAME, "r1", "game-2");
        AtomicReference<AgentLifecycleManager> sourceRef = new AtomicReference<>();
        AtomicReference<AgentLifecycleManager> targetRef = new AtomicReference<>();
        try (AgentLeaseSupervisor sourceLeases = new AgentLeaseSupervisor(
                directory, "game-1-session", Duration.ofSeconds(30), Duration.ofSeconds(10), CLOCK,
                lease -> sourceRef.get().handleLeaseLost(lease)
        ); AgentLeaseSupervisor targetLeases = new AgentLeaseSupervisor(
                directory, "game-2-session", Duration.ofSeconds(30), Duration.ofSeconds(10), CLOCK,
                lease -> targetRef.get().handleLeaseLost(lease)
        )) {
            AgentLifecycleManager source = new AgentLifecycleManager(
                    sourceService, sourceActors, directory, CLOCK, sourceLeases
            );
            AgentLifecycleManager target = new AgentLifecycleManager(
                    targetService, targetActors, directory, CLOCK, targetLeases
            );
            sourceRef.set(source);
            targetRef.set(target);
            AgentIdentity player = AgentIdentity.player(10001L);
            AgentLocation targetLocation = new AgentLocation(targetService, new ActorRef("player-10001"));

            source.activate(player, "player-10001");
            sourceExecutor.runNext();
            assertEquals(1, directory.locateLease(player).orElseThrow().fencingToken());

            source.migrate(player, targetLocation, ignored -> {
            });
            sourceExecutor.runNext();
            AgentDirectoryLease handoff = directory.locateLease(player).orElseThrow();
            assertEquals(2, handoff.fencingToken());
            assertTrue(handoff.ownerSession().startsWith("handoff:"));

            target.acceptMigrated(player, "player-10001", ignored -> {
            });
            targetExecutor.runNext();
            assertEquals(3, directory.locateLease(player).orElseThrow().fencingToken());
            assertEquals("game-2-session", directory.locateLease(player).orElseThrow().ownerSession());
            assertEquals(AgentLifecycleState.ACTIVE, target.record(player).orElseThrow().state());

            target.passivate(player, ignored -> {
            });
            targetExecutor.runNext();
            assertTrue(directory.locate(player).isEmpty());
        }
    }

    @Test
    void confirmedLeaseLossIsFencedInsideActorMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        SerializedAgentDirectory directory = new SerializedAgentDirectory(
                new InMemoryAtomicBytesStore(), CLOCK
        );
        AtomicReference<AgentLifecycleManager> lifecycleRef = new AtomicReference<>();
        try (AgentLeaseSupervisor leases = new AgentLeaseSupervisor(
                directory, "game-1-session", Duration.ofSeconds(30), Duration.ofSeconds(10), CLOCK,
                lease -> lifecycleRef.get().handleLeaseLost(lease)
        )) {
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                    ServiceId.of(ServiceKind.GAME, "r1", "game-1"), actors, directory, CLOCK, leases
            );
            lifecycleRef.set(lifecycles);
            AgentIdentity player = AgentIdentity.player(10001L);
            AtomicReference<AgentLifecycleRecord> invalidated = new AtomicReference<>();
            lifecycles.addInvalidationListener(invalidated::set);
            lifecycles.activate(player, "player-10001");
            executor.runNext();

            lifecycles.handleLeaseLost(leases.lease(player).orElseThrow());
            assertEquals(AgentLifecycleState.ACTIVE, lifecycles.record(player).orElseThrow().state());
            executor.runNext();

            assertEquals(AgentLifecycleState.FENCED, lifecycles.record(player).orElseThrow().state());
            assertEquals(AgentLifecycleState.FENCED, invalidated.get().state());
            assertEquals(0, actors.stats().registeredMailboxes());
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.removeFirst().run();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }

    private static final class FailingMoveDirectory implements AgentDirectory {
        private final InMemoryAgentDirectory delegate = new InMemoryAgentDirectory();

        @Override
        public boolean claim(AgentIdentity identity, AgentLocation location) {
            return delegate.claim(identity, location);
        }

        @Override
        public boolean move(AgentIdentity identity, AgentLocation expectedCurrent, AgentLocation next) {
            return false;
        }

        @Override
        public void unbind(AgentIdentity identity, AgentLocation location) {
            delegate.unbind(identity, location);
        }

        @Override
        public Optional<AgentLocation> locate(AgentIdentity identity) {
            return delegate.locate(identity);
        }
    }
}
