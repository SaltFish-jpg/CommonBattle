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
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.config.GameConfigPackage;
import com.commonbattle.game.config.GameConfigRuntime;
import com.commonbattle.game.config.GameConfigView;
import com.commonbattle.game.config.GrowthTuning;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerCommandStatus;
import com.commonbattle.game.shop.ShopItemDefinition;
import com.commonbattle.game.shop.ShopStockAsyncClient;
import com.commonbattle.game.shop.ShopStockCallback;
import com.commonbattle.game.shop.ShopStockReleaseResponse;
import com.commonbattle.game.shop.ShopStockRemainingResponse;
import com.commonbattle.game.shop.ShopStockReserveResponse;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
            harness.sourceSequencer.commit(command(2), PlayerCommandStatus.ACCEPTED);
            harness.sourceResponses.completed(
                    command(2),
                    PlayerBusinessResponse.success(command(2), PlayerBusinessAck.OK)
            );

            AtomicReference<AgentMigrationResult> result = new AtomicReference<>();
            boolean submitted = harness.coordinator.migrate(
                    player,
                    harness.targetLocation(player),
                    new PlayerAgentMigrationStatePacker(
                            harness.sourcePlayers,
                            harness.serializer,
                            harness.sourceSequencer,
                            harness.sourceResponses,
                            harness.payloadCodecs
                    ),
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
            assertEquals(PlayerCommandStatus.GAP, harness.sourceSequencer.inspect(command(3)));
            assertEquals(PlayerCommandStatus.DUPLICATE, harness.targetSequencer.inspect(command(2)));
            assertEquals(PlayerCommandStatus.ACCEPTED, harness.targetSequencer.inspect(command(3)));
            AtomicReference<PlayerBusinessResponseDelivery> replay = new AtomicReference<>();
            harness.targetResponses.expect(command(2), new PlayerBusinessResponseCallback() {
                @Override
                public void completed(PlayerBusinessResponse response) {
                    replay.set(new PlayerBusinessResponseDelivery(response, false));
                }

                @Override
                public void completed(PlayerBusinessResponse response, boolean replayed) {
                    replay.set(new PlayerBusinessResponseDelivery(response, replayed));
                }
            });
            assertTrue(replay.get().replayed());
            assertEquals(PlayerBusinessAck.OK, replay.get().response().payload());
            assertTrue(harness.sourceResponses.cachedResponse(
                    PlayerBusinessResponseQuery.from(command(2))).isEmpty());
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
                    new PlayerAgentMigrationStatePacker(
                            harness.sourcePlayers,
                            harness.serializer,
                            harness.sourceSequencer,
                            harness.sourceResponses,
                            harness.payloadCodecs
                    ),
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

    @Test
    void pendingAsyncShopCommandPreventsPlayerMigrationAndKeepsSourceActive() {
        try (Harness harness = new Harness()) {
            long playerId = 10001L;
            AgentIdentity player = AgentIdentity.player(playerId);
            PlayerGameAgent sourceAgent = harness.sourcePlayers.getOrCreate(playerId);
            sourceAgent.profile().bag().restore(new BagSnapshot(Map.of("gold", 100)));
            harness.sourceActors.send(new ActorRef("player-10001"), ignored -> sourceAgent.buyShopItemAsync(
                    command(1),
                    "order-10001-pending",
                    "limited_pack",
                    1,
                    PlayerBusinessResultSink.NOOP
            ));

            AtomicReference<AgentMigrationResult> result = new AtomicReference<>();
            boolean submitted = harness.coordinator.migrate(
                    player,
                    harness.targetLocation(player),
                    new PlayerAgentMigrationStatePacker(
                            harness.sourcePlayers,
                            harness.serializer,
                            harness.sourceSequencer,
                            harness.sourceResponses,
                            harness.payloadCodecs
                    ),
                    result::set
            );

            assertTrue(submitted);
            assertEquals(AgentMigrationResultStatus.SOURCE_MOVE_FAILED, result.get().status());
            assertEquals("player 10001 has pending async commands: 1", result.get().reason());
            assertEquals(harness.sourceGame.id(), harness.agents.locate(player).orElseThrow().serviceId());
            assertEquals(AgentLifecycleState.ACTIVE,
                    harness.sourceLifecycles.record(player).orElseThrow().state());
            assertTrue(harness.sourcePlayers.get(playerId).isPresent());
            assertTrue(harness.targetPlayers.get(playerId).isEmpty());
            assertEquals("order-10001-pending", harness.sourceStocks.reserveReservationId);
            assertEquals(new AgentMigrationCoordinatorStats(1, 0, 1, 0, 0, 0, 0, 0, 0, 0),
                    harness.coordinator.stats());
        }
    }

    @Test
    void retryServiceMigratesPlayerAfterPendingAsyncCommandCompletes() {
        try (Harness harness = new Harness()) {
            long playerId = 10001L;
            AgentIdentity player = AgentIdentity.player(playerId);
            PlayerGameAgent sourceAgent = harness.sourcePlayers.getOrCreate(playerId);
            sourceAgent.profile().bag().restore(new BagSnapshot(Map.of("gold", 100)));
            harness.sourceActors.send(new ActorRef("player-10001"), ignored -> sourceAgent.buyShopItemAsync(
                    command(1),
                    "order-10001-pending",
                    "limited_pack",
                    1,
                    PlayerBusinessResultSink.NOOP
            ));
            PlayerAgentMigrationRetryService retries = new PlayerAgentMigrationRetryService(harness.coordinator);
            AtomicReference<AgentMigrationResult> result = new AtomicReference<>();

            boolean submitted = retries.migrate(
                    player,
                    harness.targetLocation(player),
                    new PlayerAgentMigrationStatePacker(
                            harness.sourcePlayers,
                            harness.serializer,
                            harness.sourceSequencer,
                            harness.sourceResponses,
                            harness.payloadCodecs
                    ),
                    result::set
            );

            assertTrue(submitted);
            assertNull(result.get());
            assertEquals(1, retries.stats().pendingRetries());
            assertEquals(1, retries.stats().retryableSourceFailures());
            assertEquals(harness.sourceGame.id(), harness.agents.locate(player).orElseThrow().serviceId());

            harness.sourceStocks.reserveCallback.success(new ShopStockReserveResponse(true, 0));
            assertEquals(1, retries.retryPending());

            assertEquals(AgentMigrationResultStatus.TARGET_ACCEPTED, result.get().status());
            assertEquals(0, retries.stats().pendingRetries());
            assertEquals(1, retries.stats().retryCompleted());
            assertEquals(harness.targetGame.id(), harness.agents.locate(player).orElseThrow().serviceId());
            assertTrue(harness.sourcePlayers.get(playerId).isEmpty());
            assertTrue(harness.targetPlayers.get(playerId).isPresent());
            assertEquals(50, harness.targetPlayers.getOrCreate(playerId).profile().bag().count("gold"));
        }
    }

    @Test
    void retryServiceWaitsForPendingBusinessResponseBeforeMigration() {
        try (Harness harness = new Harness()) {
            long playerId = 10001L;
            AgentIdentity player = AgentIdentity.player(playerId);
            harness.sourcePlayers.getOrCreate(playerId);
            PlayerCommand command = command(1);
            harness.sourceResponses.expect(command, ignored -> {
            });
            PlayerAgentMigrationRetryService retries = new PlayerAgentMigrationRetryService(harness.coordinator);
            AtomicReference<AgentMigrationResult> result = new AtomicReference<>();

            boolean submitted = retries.migrate(
                    player,
                    harness.targetLocation(player),
                    new PlayerAgentMigrationStatePacker(
                            harness.sourcePlayers,
                            harness.serializer,
                            harness.sourceSequencer,
                            harness.sourceResponses,
                            harness.payloadCodecs
                    ),
                    result::set
            );

            assertTrue(submitted);
            assertNull(result.get());
            assertEquals(1, retries.stats().pendingRetries());
            harness.sourceResponses.completed(
                    command,
                    PlayerBusinessResponse.success(command, PlayerBusinessAck.OK)
            );

            assertEquals(1, retries.retryPending());

            assertEquals(AgentMigrationResultStatus.TARGET_ACCEPTED, result.get().status());
            assertEquals(PlayerBusinessAck.OK, harness.targetResponses.cachedResponse(
                    PlayerBusinessResponseQuery.from(command)).orElseThrow().payload());
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
        private final RecordingShopStockAsyncClient sourceStocks = new RecordingShopStockAsyncClient();
        private final ProtostuffPlayerStateSnapshotSerializer serializer =
                new ProtostuffPlayerStateSnapshotSerializer();
        private final PlayerCommandSequencer sourceSequencer = new PlayerCommandSequencer();
        private final PlayerCommandSequencer targetSequencer = new PlayerCommandSequencer();
        private final PlayerBusinessResponseHub sourceResponses = new PlayerBusinessResponseHub();
        private final PlayerBusinessResponseHub targetResponses = new PlayerBusinessResponseHub();
        private final PayloadCodecRegistry payloadCodecs = PlayerBusinessCommandPayloadCodecs.registerTo(
                PayloadCodecRegistry.commonDefaults());
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
            GameConfigView configView = new FixedGameConfigView(GameConfigRuntime.from(config()));
            sourcePlayers = new PlayerGameAgentManager(
                    sourceActors,
                    new DefaultAgentMessagePort(sourceActors, new NoopRpcGateway()),
                    sourceRepository,
                    configView,
                    sourceLifecycles,
                    CLOCK,
                    SERVER_OPEN_TIME,
                    null,
                    null,
                    PlayerStateSaveListener.ignore(),
                    sourceStocks
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
                            : new PlayerAgentMigrationRestoreHandler(
                                    targetPlayers,
                                    serializer,
                                    targetSequencer,
                                    targetResponses,
                                    payloadCodecs
                            )
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
                    new PlayerAgentMigrationSourceHook(
                            sourcePlayers,
                            serializer,
                            sourceSequencer,
                            sourceResponses,
                            payloadCodecs
                    )
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

    private static GameConfigPackage config() {
        GameConfigPackage basic = ExampleGameConfigs.basic(1, CLOCK.instant());
        return new GameConfigPackage(
                1,
                List.of(
                        new ItemDefinition("gold", "currency", 999999),
                        new ItemDefinition("exp_potion", "growth", 999),
                        new ItemDefinition("gem", "currency", 999999),
                        new ItemDefinition("ticket", "ticket", 999999)
                ),
                basic.activities(),
                List.of(new ShopItemDefinition(
                        "limited_pack",
                        new ItemStack("gold", 50),
                        Reward.of(new ItemStack("ticket", 1)),
                        0,
                        0,
                        1
                )),
                basic.battles(),
                basic.tasks(),
                basic.achievements(),
                new GrowthTuning("exp_potion", 60, 100),
                CLOCK.instant()
        );
    }

    private static PlayerCommand command(long sequence) {
        return new PlayerCommand(
                10001L,
                "session-1",
                1,
                sequence,
                PlayerBusinessOperations.ACTIVITY_PROGRESS,
                new ActivityProgressCommand("kill-3", 1)
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

    private static final class RecordingShopStockAsyncClient implements ShopStockAsyncClient {
        private String reserveReservationId;
        private ShopStockCallback<ShopStockReserveResponse> reserveCallback;

        @Override
        public void reserve(
                String reservationId,
                String sku,
                int count,
                ShopStockCallback<ShopStockReserveResponse> callback
        ) {
            reserveReservationId = reservationId;
            reserveCallback = callback;
        }

        @Override
        public void release(
                String reservationId,
                String sku,
                int count,
                ShopStockCallback<ShopStockReleaseResponse> callback
        ) {
            callback.success(new ShopStockReleaseResponse(1));
        }

        @Override
        public void remaining(String sku, ShopStockCallback<ShopStockRemainingResponse> callback) {
        }
    }
}
