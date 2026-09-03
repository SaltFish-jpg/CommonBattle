package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.AgentRouteType;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentMigrationCoordinatorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void coordinatorMovesDirectoryAndRestoresStateOnTarget() {
        Harness harness = new Harness(true);
        AgentIdentity player = AgentIdentity.player(10001L);
        harness.sourceLifecycles.activate(player, "player-10001");
        harness.sourceExecutor.runNext();

        harness.coordinator.migrate(player, harness.targetLocation(player), (identity, target, context) ->
                new AgentMigrationSnapshot("player.snapshot.v1", "hp=100".getBytes(StandardCharsets.UTF_8))
        );
        harness.sourceExecutor.runNext();
        harness.targetExecutor.runNext();

        assertEquals(harness.targetGame.id(), harness.agents.locate(player).orElseThrow().serviceId());
        assertEquals(AgentLifecycleState.MIGRATED, harness.sourceLifecycles.record(player).orElseThrow().state());
        assertEquals(AgentLifecycleState.ACTIVE, harness.targetLifecycles.record(player).orElseThrow().state());
        assertEquals("player.snapshot.v1:hp=100", harness.restored.get());
    }

    @Test
    void coordinatorRollsDirectoryBackWhenTargetCannotAccept() {
        Harness harness = new Harness(false);
        AgentIdentity player = AgentIdentity.player(10001L);
        harness.sourceLifecycles.activate(player, "player-10001");
        harness.sourceExecutor.runNext();

        harness.coordinator.migrate(player, harness.targetLocation(player), (identity, target, context) ->
                new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1})
        );
        harness.sourceExecutor.runNext();
        LifecycleAwareAgentRouter sourceRouter = new LifecycleAwareAgentRouter(
                harness.sourceLifecycles,
                new DefaultAgentMessagePort(harness.sourceActors, new NoopRpcGateway())
        );

        assertEquals(harness.sourceGame.id(), harness.agents.locate(player).orElseThrow().serviceId());
        assertEquals(AgentLifecycleState.ACTIVE, harness.sourceLifecycles.record(player).orElseThrow().state());
        assertEquals(AgentRouteType.LOCAL, sourceRouter.resolve(player).type());
        assertEquals(0, harness.targetExecutor.pending());
    }

    private static final class Harness {
        private final InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        private final LocalClusterTransport transport = new LocalClusterTransport();
        private final ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        private final ServiceDescriptor sourceGame = descriptor("game-1", 9001);
        private final ServiceDescriptor targetGame = descriptor("game-2", 9002);
        private final RecordingExecutor sourceExecutor = new RecordingExecutor();
        private final RecordingExecutor targetExecutor = new RecordingExecutor();
        private final ActorSystem sourceActors = new ActorSystem(sourceExecutor, 64);
        private final ActorSystem targetActors = new ActorSystem(targetExecutor, 64);
        private final InMemoryAgentDirectory agents = new InMemoryAgentDirectory();
        private final AgentLifecycleManager sourceLifecycles =
                new AgentLifecycleManager(sourceGame.id(), sourceActors, agents, CLOCK);
        private final AgentLifecycleManager targetLifecycles =
                new AgentLifecycleManager(targetGame.id(), targetActors, agents, CLOCK);
        private final AtomicReference<String> restored = new AtomicReference<>();
        private final AgentMigrationCoordinator coordinator;

        private Harness(boolean bindTargetEndpoint) {
            registry.register(sourceGame);
            registry.register(targetGame);
            ClusterDirectory sourceDirectory = new ClusterDirectory(registry);
            sourceDirectory.watch(ServiceKind.GAME);
            ClusterDirectory targetDirectory = new ClusterDirectory(registry);
            targetDirectory.watch(ServiceKind.GAME);
            ClusterRpcGateway targetGateway = new ClusterRpcGateway(targetGame, targetDirectory, topology, transport);
            if (bindTargetEndpoint) {
                new AgentMigrationTargetEndpoint(targetLifecycles, (request, context) ->
                        restored.set(request.stateType() + ":"
                                + new String(request.stateBytes(), StandardCharsets.UTF_8))
                ).bind(targetGateway);
            }
            ClusterRpcGateway sourceGateway = new ClusterRpcGateway(sourceGame, sourceDirectory, topology, transport);
            coordinator = new AgentMigrationCoordinator(
                    sourceLifecycles,
                    agents,
                    new RemoteAgentMigrationClient(sourceGateway),
                    Runnable::run
            );
        }

        private AgentLocation targetLocation(AgentIdentity identity) {
            return new AgentLocation(targetGame.id(), new ActorRef(identity.type() + "-" + identity.key()));
        }
    }

    private static ServiceDescriptor descriptor(String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.GAME, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(AgentMigrationOperations.ACCEPT),
                Map.of()
        );
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

        int pending() {
            return commands.size();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
