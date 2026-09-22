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
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteAgentMigrationClientTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void sourceGameTargetsAssignedOwnerAndRestoresStateInTargetMailbox() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor sourceGame = descriptor("game-1", 9001);
        ServiceDescriptor targetGame = descriptor("game-2", 9002);
        registry.register(sourceGame);
        registry.register(targetGame);
        ClusterDirectory sourceDirectory = new ClusterDirectory(registry);
        sourceDirectory.watch(ServiceKind.GAME);
        ClusterDirectory targetDirectory = new ClusterDirectory(registry);
        targetDirectory.watch(ServiceKind.GAME);

        RecordingExecutor sourceExecutor = new RecordingExecutor();
        ActorSystem sourceActors = new ActorSystem(sourceExecutor, 64);
        ActorSystem targetActors = new ActorSystem(Runnable::run, 64);
        InMemoryAgentDirectory agents = new InMemoryAgentDirectory();
        AgentLifecycleManager sourceLifecycles = new AgentLifecycleManager(sourceGame.id(), sourceActors, agents, CLOCK);
        AgentLifecycleManager targetLifecycles = new AgentLifecycleManager(targetGame.id(), targetActors, agents, CLOCK);
        ClusterRpcGateway targetGateway = new ClusterRpcGateway(targetGame, targetDirectory, topology, transport);
        AtomicReference<String> restored = new AtomicReference<>();
        new AgentMigrationTargetEndpoint(targetLifecycles, (request, context) ->
                restored.set(request.stateType() + ":" + new String(request.stateBytes(), StandardCharsets.UTF_8))
        ).bind(targetGateway);
        ClusterRpcGateway sourceGateway = new ClusterRpcGateway(sourceGame, sourceDirectory, topology, transport);
        RemoteAgentMigrationClient migrationClient = new RemoteAgentMigrationClient(sourceGateway);
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation targetLocation = new AgentLocation(targetGame.id(), new ActorRef("player-10001"));
        sourceLifecycles.activate(player, "player-10001");
        sourceExecutor.runNext();

        sourceLifecycles.migrate(player, targetLocation, ignored -> {
        });
        sourceExecutor.runNext();
        AgentMigrationAcceptResponse response = migrationClient.accept(
                targetGame.id(),
                new AgentMigrationAcceptRequest(
                        player,
                        "player-10001",
                        "player.snapshot.v1",
                        "hp=100".getBytes(StandardCharsets.UTF_8)
                )
        );
        LifecycleAwareAgentRouter targetRouter = new LifecycleAwareAgentRouter(
                targetLifecycles,
                new DefaultAgentMessagePort(targetActors, new NoopRpcGateway())
        );

        assertTrue(response.accepted(), response.reason());
        assertEquals("player.snapshot.v1:hp=100", restored.get());
        assertEquals(AgentLifecycleState.ACTIVE, targetLifecycles.record(player).orElseThrow().state());
        assertEquals(AgentRouteType.LOCAL, targetRouter.resolve(player).type());
    }

    @Test
    void targetRejectsMigrationBeforeDirectoryPointsToIt() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor sourceGame = descriptor("game-1", 9001);
        ServiceDescriptor targetGame = descriptor("game-2", 9002);
        registry.register(sourceGame);
        registry.register(targetGame);
        ClusterDirectory sourceDirectory = new ClusterDirectory(registry);
        sourceDirectory.watch(ServiceKind.GAME);
        ClusterDirectory targetDirectory = new ClusterDirectory(registry);
        targetDirectory.watch(ServiceKind.GAME);

        RecordingExecutor sourceExecutor = new RecordingExecutor();
        RecordingExecutor targetExecutor = new RecordingExecutor();
        ActorSystem sourceActors = new ActorSystem(sourceExecutor, 64);
        ActorSystem targetActors = new ActorSystem(targetExecutor, 64);
        InMemoryAgentDirectory agents = new InMemoryAgentDirectory();
        AgentLifecycleManager sourceLifecycles = new AgentLifecycleManager(sourceGame.id(), sourceActors, agents, CLOCK);
        AgentLifecycleManager targetLifecycles = new AgentLifecycleManager(targetGame.id(), targetActors, agents, CLOCK);
        ClusterRpcGateway targetGateway = new ClusterRpcGateway(targetGame, targetDirectory, topology, transport);
        new AgentMigrationTargetEndpoint(targetLifecycles, AgentMigrationRestoreHandler.noop()).bind(targetGateway);
        ClusterRpcGateway sourceGateway = new ClusterRpcGateway(sourceGame, sourceDirectory, topology, transport);
        RemoteAgentMigrationClient migrationClient = new RemoteAgentMigrationClient(sourceGateway);
        AgentIdentity player = AgentIdentity.player(10001L);
        sourceLifecycles.activate(player, "player-10001");
        sourceExecutor.runNext();

        AgentMigrationAcceptResponse response = migrationClient.accept(
                targetGame.id(),
                new AgentMigrationAcceptRequest(player, "player-10001", "player.snapshot.v1", new byte[]{1})
        );

        assertEquals(false, response.accepted());
        assertFalse(response.reason().isBlank());
        assertTrue(targetLifecycles.record(player).isEmpty());
        assertEquals(0, targetExecutor.pending());
    }

    @Test
    void targetAcceptIsIdempotentForSameMigrationTask() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor sourceGame = descriptor("game-1", 9001);
        ServiceDescriptor targetGame = descriptor("game-2", 9002);
        registry.register(sourceGame);
        registry.register(targetGame);
        ClusterDirectory sourceDirectory = new ClusterDirectory(registry);
        sourceDirectory.watch(ServiceKind.GAME);
        ClusterDirectory targetDirectory = new ClusterDirectory(registry);
        targetDirectory.watch(ServiceKind.GAME);

        RecordingExecutor sourceExecutor = new RecordingExecutor();
        ActorSystem sourceActors = new ActorSystem(sourceExecutor, 64);
        ActorSystem targetActors = new ActorSystem(Runnable::run, 64);
        InMemoryAgentDirectory agents = new InMemoryAgentDirectory();
        AgentLifecycleManager sourceLifecycles = new AgentLifecycleManager(sourceGame.id(), sourceActors, agents, CLOCK);
        AgentLifecycleManager targetLifecycles = new AgentLifecycleManager(targetGame.id(), targetActors, agents, CLOCK);
        ClusterRpcGateway targetGateway = new ClusterRpcGateway(targetGame, targetDirectory, topology, transport);
        AtomicInteger restores = new AtomicInteger();
        new AgentMigrationTargetEndpoint(targetLifecycles, (request, context) -> restores.incrementAndGet())
                .bind(targetGateway);
        ClusterRpcGateway sourceGateway = new ClusterRpcGateway(sourceGame, sourceDirectory, topology, transport);
        RemoteAgentMigrationClient migrationClient = new RemoteAgentMigrationClient(sourceGateway);
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation targetLocation = new AgentLocation(targetGame.id(), new ActorRef("player-10001"));
        sourceLifecycles.activate(player, "player-10001");
        sourceExecutor.runNext();
        sourceLifecycles.migrate(player, targetLocation, ignored -> {
        });
        sourceExecutor.runNext();
        AgentMigrationAcceptRequest request = new AgentMigrationAcceptRequest(
                "migration-10001",
                player,
                "player-10001",
                "player.snapshot.v1",
                "hp=100".getBytes(StandardCharsets.UTF_8)
        );

        AgentMigrationAcceptResponse first = migrationClient.accept(targetGame.id(), request);
        AgentMigrationAcceptResponse replay = migrationClient.accept(targetGame.id(), request);

        assertTrue(first.accepted(), first.reason());
        assertTrue(replay.accepted(), replay.reason());
        assertEquals(1, restores.get());
        assertEquals(AgentLifecycleState.ACTIVE, targetLifecycles.record(player).orElseThrow().state());
    }

    @Test
    void targetAcceptReceiptCanSurviveEndpointRecreation() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor sourceGame = descriptor("game-1", 9001);
        ServiceDescriptor targetGame = descriptor("game-2", 9002);
        registry.register(sourceGame);
        registry.register(targetGame);
        ClusterDirectory sourceDirectory = new ClusterDirectory(registry);
        sourceDirectory.watch(ServiceKind.GAME);
        ClusterDirectory targetDirectory = new ClusterDirectory(registry);
        targetDirectory.watch(ServiceKind.GAME);

        RecordingExecutor sourceExecutor = new RecordingExecutor();
        ActorSystem sourceActors = new ActorSystem(sourceExecutor, 64);
        ActorSystem targetActors = new ActorSystem(Runnable::run, 64);
        InMemoryAgentDirectory agents = new InMemoryAgentDirectory();
        AgentLifecycleManager sourceLifecycles = new AgentLifecycleManager(sourceGame.id(), sourceActors, agents, CLOCK);
        AgentLifecycleManager targetLifecycles = new AgentLifecycleManager(targetGame.id(), targetActors, agents, CLOCK);
        ClusterRpcGateway targetGateway = new ClusterRpcGateway(targetGame, targetDirectory, topology, transport);
        InMemoryAtomicBytesStore receiptBytes = new InMemoryAtomicBytesStore();
        AtomicInteger restores = new AtomicInteger();
        new AgentMigrationTargetEndpoint(
                targetLifecycles,
                (request, context) -> restores.incrementAndGet(),
                receiptStore(receiptBytes)
        ).bind(targetGateway);
        ClusterRpcGateway sourceGateway = new ClusterRpcGateway(sourceGame, sourceDirectory, topology, transport);
        RemoteAgentMigrationClient migrationClient = new RemoteAgentMigrationClient(sourceGateway);
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation targetLocation = new AgentLocation(targetGame.id(), new ActorRef("player-10001"));
        sourceLifecycles.activate(player, "player-10001");
        sourceExecutor.runNext();
        sourceLifecycles.migrate(player, targetLocation, ignored -> {
        });
        sourceExecutor.runNext();
        AgentMigrationAcceptRequest request = new AgentMigrationAcceptRequest(
                "migration-10001",
                player,
                "player-10001",
                "player.snapshot.v1",
                "hp=100".getBytes(StandardCharsets.UTF_8)
        );
        AgentMigrationAcceptResponse first = migrationClient.accept(targetGame.id(), request);

        AgentLifecycleManager recreatedLifecycles =
                new AgentLifecycleManager(targetGame.id(), targetActors, agents, CLOCK);
        new AgentMigrationTargetEndpoint(
                recreatedLifecycles,
                (next, context) -> restores.incrementAndGet(),
                receiptStore(receiptBytes)
        ).bind(targetGateway);
        AgentMigrationAcceptResponse replay = migrationClient.accept(targetGame.id(), request);

        assertTrue(first.accepted(), first.reason());
        assertTrue(replay.accepted(), replay.reason());
        assertEquals(1, restores.get());
        assertTrue(recreatedLifecycles.record(player).isEmpty());
    }

    private static ServiceDescriptor descriptor(String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.GAME, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(AgentMigrationOperations.ACCEPT),
                Map.of()
        );
    }

    private static SerializedAgentMigrationTargetReceiptStore receiptStore(InMemoryAtomicBytesStore bytes) {
        return new SerializedAgentMigrationTargetReceiptStore(
                bytes,
                new ProtoAgentMigrationTargetReceiptSerializer(),
                CLOCK
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
