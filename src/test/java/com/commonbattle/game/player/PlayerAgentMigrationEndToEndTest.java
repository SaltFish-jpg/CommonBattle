package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;
import com.commonbattle.actor.agent.migration.AgentMigrationCoordinator;
import com.commonbattle.actor.agent.migration.AgentMigrationCoordinatorStats;
import com.commonbattle.actor.agent.migration.AgentMigrationPolicy;
import com.commonbattle.actor.agent.migration.AgentMigrationResult;
import com.commonbattle.actor.agent.migration.AgentMigrationResultStatus;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStoreStats;
import com.commonbattle.actor.agent.migration.AgentMigrationTargetEndpoint;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskIdGenerator;
import com.commonbattle.actor.agent.migration.InMemoryAgentMigrationTaskStore;
import com.commonbattle.actor.agent.migration.RemoteAgentMigrationClient;
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
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.config.GameConfigRuntime;
import com.commonbattle.game.config.GameConfigView;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerAgentMigrationEndToEndTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant SERVER_OPEN_TIME = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void migratesPlayerAgentFromOneGameToAnotherThroughClusterRpc() {
        try (Harness harness = new Harness()) {
            long playerId = 10001L;
            AgentIdentity player = AgentIdentity.player(playerId);
            PlayerGameAgent sourceAgent = harness.sourcePlayers.getOrCreate(playerId);
            sourceAgent.profile().bag().restore(new BagSnapshot(Map.of("gold", 77, "exp", 5)));

            AtomicReference<AgentMigrationResult> result = new AtomicReference<>();
            boolean submitted = harness.coordinator.migrate(
                    player,
                    harness.targetLocation(player),
                    new PlayerAgentMigrationStatePacker(harness.sourcePlayers, harness.serializer),
                    result::set
            );

            assertTrue(submitted);
            assertEquals(AgentMigrationResultStatus.TARGET_ACCEPTED, result.get().status());
            assertEquals(harness.targetGame.id(), harness.agents.locate(player).orElseThrow().serviceId());
            assertEquals(AgentLifecycleState.MIGRATED,
                    harness.sourceLifecycles.record(player).orElseThrow().state());
            assertEquals(AgentLifecycleState.ACTIVE,
                    harness.targetLifecycles.record(player).orElseThrow().state());
            assertTrue(harness.sourcePlayers.get(playerId).isEmpty());
            assertTrue(harness.targetPlayers.get(playerId).isPresent());
            assertEquals(77, harness.targetPlayers.getOrCreate(playerId).profile().bag().count("gold"));
            assertEquals(5, harness.targetRepository.load(playerId).orElseThrow().bag().itemCounts().get("exp"));
            assertEquals(new AgentMigrationTaskStoreStats(1, 1, 0, 0, 1, 0, 0),
                    harness.taskStore.stats(CLOCK.instant()));
            assertEquals(new AgentMigrationCoordinatorStats(1, 1, 0, 1, 0, 0, 0, 0, 0, 0),
                    harness.coordinator.stats());
        }
    }

    @Test
    void rollsPlayerAgentBackWhenTargetMailboxRestoreFails() {
        try (Harness harness = new Harness(true)) {
            long playerId = 10001L;
            AgentIdentity player = AgentIdentity.player(playerId);
            PlayerGameAgent sourceAgent = harness.sourcePlayers.getOrCreate(playerId);
            sourceAgent.profile().bag().restore(new BagSnapshot(Map.of("gold", 91)));

            AtomicReference<AgentMigrationResult> result = new AtomicReference<>();
            boolean submitted = harness.coordinator.migrate(
                    player,
                    harness.targetLocation(player),
                    new PlayerAgentMigrationStatePacker(harness.sourcePlayers, harness.serializer),
                    result::set
            );

            assertTrue(submitted);
            assertEquals(AgentMigrationResultStatus.TARGET_REJECTED_ROLLED_BACK, result.get().status());
            assertEquals(harness.sourceGame.id(), harness.agents.locate(player).orElseThrow().serviceId());
            assertEquals(AgentLifecycleState.ACTIVE,
                    harness.sourceLifecycles.record(player).orElseThrow().state());
            assertTrue(harness.sourcePlayers.get(playerId).isPresent());
            assertEquals(91, harness.sourcePlayers.getOrCreate(playerId).profile().bag().count("gold"));
            assertTrue(harness.targetPlayers.get(playerId).isEmpty());
            assertTrue(harness.targetLifecycles.record(player).isEmpty());
            assertEquals(new AgentMigrationCoordinatorStats(1, 1, 0, 0, 1, 0, 0, 0, 1, 0),
                    harness.coordinator.stats());
        }
    }

    private static final class Harness implements AutoCloseable {
        private final InMemoryServiceRegistry registry = new InMemoryServiceRegistry(CLOCK);
        private final LocalClusterTransport transport = new LocalClusterTransport();
        private final ClusterTopology topology = new ClusterTopology().allow(ServiceKind.GAME, ServiceKind.GAME);
        private final ServiceDescriptor sourceGame = descriptor("game-1", 9001);
        private final ServiceDescriptor targetGame = descriptor("game-2", 9002);
        private final ClusterDirectory sourceDirectory = new ClusterDirectory(registry);
        private final ClusterDirectory targetDirectory = new ClusterDirectory(registry);
        private final ClusterRpcGateway sourceGateway;
        private final ClusterRpcGateway targetGateway;
        private final ActorSystem sourceActors = new ActorSystem(Runnable::run, 64);
        private final ActorSystem targetActors = new ActorSystem(Runnable::run, 64);
        private final InMemoryAgentDirectory agents = new InMemoryAgentDirectory();
        private final InMemoryPlayerStateRepository sourceRepository = new InMemoryPlayerStateRepository();
        private final InMemoryPlayerStateRepository targetRepository = new InMemoryPlayerStateRepository();
        private final ProtostuffPlayerStateSnapshotSerializer serializer =
                new ProtostuffPlayerStateSnapshotSerializer();
        private final InMemoryAgentMigrationTaskStore taskStore = new InMemoryAgentMigrationTaskStore();
        private final AgentLifecycleManager sourceLifecycles =
                new AgentLifecycleManager(sourceGame.id(), sourceActors, agents, CLOCK);
        private final AgentLifecycleManager targetLifecycles =
                new AgentLifecycleManager(targetGame.id(), targetActors, agents, CLOCK);
        private final PlayerGameAgentManager sourcePlayers;
        private final PlayerGameAgentManager targetPlayers;
        private final AgentMigrationCoordinator coordinator;

        private Harness() {
            this(false);
        }

        private Harness(boolean failRestore) {
            registry.register(sourceGame);
            registry.register(targetGame);
            sourceDirectory.watch(ServiceKind.GAME);
            targetDirectory.watch(ServiceKind.GAME);
            sourceGateway = new ClusterRpcGateway(sourceGame, sourceDirectory, topology, transport);
            targetGateway = new ClusterRpcGateway(targetGame, targetDirectory, topology, transport);
            GameConfigView configView = new FixedGameConfigView(
                    GameConfigRuntime.from(ExampleGameConfigs.basic(1, CLOCK.instant()))
            );
            sourcePlayers = new PlayerGameAgentManager(
                    sourceActors,
                    new DefaultAgentMessagePort(sourceActors, new NoopRpcGateway()),
                    sourceRepository,
                    configView,
                    sourceLifecycles,
                    CLOCK,
                    SERVER_OPEN_TIME
            );
            targetPlayers = new PlayerGameAgentManager(
                    targetActors,
                    new DefaultAgentMessagePort(targetActors, new NoopRpcGateway()),
                    targetRepository,
                    configView,
                    targetLifecycles,
                    CLOCK,
                    SERVER_OPEN_TIME
            );
            new AgentMigrationTargetEndpoint(
                    targetLifecycles,
                    failRestore
                            ? (request, context) -> {
                                throw new IllegalStateException("restore failed");
                            }
                            : new PlayerAgentMigrationRestoreHandler(targetPlayers, serializer)
            ).bind(targetGateway);
            coordinator = new AgentMigrationCoordinator(
                    sourceLifecycles,
                    agents,
                    new RemoteAgentMigrationClient(sourceGateway),
                    Runnable::run,
                    AgentMigrationPolicy.defaults(),
                    taskStore,
                    AgentMigrationTaskIdGenerator.defaultGenerator(),
                    CLOCK,
                    new PlayerAgentMigrationSourceHook(sourcePlayers, serializer)
            );
        }

        private AgentLocation targetLocation(AgentIdentity identity) {
            return new AgentLocation(targetGame.id(), new ActorRef(identity.type() + "-" + identity.key()));
        }

        @Override
        public void close() {
            sourceGateway.close();
            targetGateway.close();
            sourceActors.close();
            targetActors.close();
            sourceDirectory.close();
            targetDirectory.close();
            transport.close();
        }
    }

    private static ServiceDescriptor descriptor(String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.GAME, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of("agent.migration.accept"),
                Map.of()
        );
    }

    private record FixedGameConfigView(GameConfigRuntime runtime) implements GameConfigView {
        @Override
        public GameConfigRuntime active() {
            return runtime;
        }

        @Override
        public GameConfigRuntime resolve(long playerId) {
            return runtime;
        }

        @Override
        public Optional<GameConfigRuntime> version(long version) {
            return version == runtime.version() ? Optional.of(runtime) : Optional.empty();
        }

        @Override
        public List<Long> versions() {
            return List.of(runtime.version());
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
