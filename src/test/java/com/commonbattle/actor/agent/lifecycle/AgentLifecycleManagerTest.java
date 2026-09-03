package com.commonbattle.actor.agent.lifecycle;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.AgentRouteType;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

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
