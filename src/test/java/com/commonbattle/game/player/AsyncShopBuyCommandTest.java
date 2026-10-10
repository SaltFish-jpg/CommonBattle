package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.backpressure.AdmissionControlledAgentRouter;
import com.commonbattle.actor.backpressure.AdmissionDecision;
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
import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.config.GameConfigPackage;
import com.commonbattle.game.config.GameConfigRuntime;
import com.commonbattle.game.config.GameConfigView;
import com.commonbattle.game.config.GrowthTuning;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.player.event.ShopItemPurchasedEvent;
import com.commonbattle.game.session.InMemoryPlayerCommandAuditLog;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandAuditOutcome;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerCommandStatus;
import com.commonbattle.game.shop.ShopItemDefinition;
import com.commonbattle.game.shop.ShopPurchaseResult;
import com.commonbattle.game.shop.ShopPurchaseStatus;
import com.commonbattle.game.shop.InMemoryShopStockRepository;
import com.commonbattle.game.shop.RemoteShopStockAsyncClient;
import com.commonbattle.game.shop.ShopStockAsyncClient;
import com.commonbattle.game.shop.ShopStockCallback;
import com.commonbattle.game.shop.ShopStockEndpoint;
import com.commonbattle.game.shop.ShopStockOperations;
import com.commonbattle.game.shop.ShopStockReleaseResponse;
import com.commonbattle.game.shop.ShopStockReserveResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncShopBuyCommandTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant SERVER_OPEN_TIME = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void limitedStockPurchaseCompletesOnlyAfterRpcCallbackRunsInPlayerMailbox() {
        Fixture fixture = Fixture.create();
        fixture.agent.profile().bag().restore(new BagSnapshot(java.util.Map.of("gold", 100)));

        var dispatch = fixture.dispatch(new BuyShopItemAsyncCommand("order-10001-1", "limited_pack", 1));
        fixture.executor.runNext();

        assertEquals(PlayerCommandStatus.ACCEPTED, dispatch.status());
        assertEquals("order-10001-1", fixture.stocks.reserveReservationId);
        assertEquals("limited_pack", fixture.stocks.reserveSku);
        assertEquals(0, fixture.results.envelopes.size());
        assertEquals(100, fixture.agent.profile().bag().count("gold"));

        fixture.stocks.reserveCallback.success(new ShopStockReserveResponse(true, 0));
        assertEquals(0, fixture.results.envelopes.size());
        assertEquals(100, fixture.agent.profile().bag().count("gold"));
        fixture.executor.runNext();

        PlayerBusinessResponse envelope = fixture.results.envelopes.getFirst();
        ShopPurchaseResult result = assertInstanceOf(ShopPurchaseResult.class, envelope.payload());
        assertEquals(ShopPurchaseStatus.SUCCESS, result.status());
        assertEquals(50, fixture.agent.profile().bag().count("gold"));
        assertEquals(1, fixture.agent.profile().bag().count("ticket"));
        assertTrue(fixture.events.stream()
                .filter(PlayerDomainVersionedEvent.class::isInstance)
                .map(PlayerDomainVersionedEvent.class::cast)
                .anyMatch(event -> ShopItemPurchasedEvent.TYPE.equals(event.eventType())));
        AsyncShopPurchaseStats stats = fixture.metrics.stats();
        assertEquals(1, stats.startedPurchases());
        assertEquals(1, stats.stockReservations());
        assertEquals(0, stats.currentPendingCommands());
        assertEquals(1, stats.reservedCallbacks());
        assertEquals(1, stats.completedPurchases());
        assertEquals(0, stats.releasedReservations());
        assertEquals(2, fixture.audit.records().size());
        assertEquals(PlayerCommandAuditOutcome.ASYNC_COMPLETED, fixture.audit.last().outcome());
        assertEquals(PlayerBusinessResponse.OK, fixture.audit.last().resultCode());
        assertEquals(7, fixture.audit.last().configVersion());
    }

    @Test
    void limitedStockPurchaseCanReserveThroughCenterRpcThenResumePlayerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 1);
        ActorRef self = actors.actor("player-10001");
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.CENTER)
                .allow(ServiceKind.CENTER, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9101, Set.of("game.player.command"));
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9201, Set.of(
                ShopStockOperations.RESERVE,
                ShopStockOperations.RELEASE,
                ShopStockOperations.REMAINING
        ));
        registry.register(game);
        registry.register(center);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.CENTER);
        ClusterDirectory centerDirectory = new ClusterDirectory(registry);
        centerDirectory.watch(ServiceKind.GAME);
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, centerDirectory, topology, transport);
        InMemoryShopStockRepository centerStocks = new InMemoryShopStockRepository(CLOCK, java.time.Duration.ZERO);
        centerStocks.set("limited_pack", 1);
        new ShopStockEndpoint(centerStocks).bind(centerGateway);
        DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, gameGateway);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
        lifecycles.activate(AgentIdentity.player(10001L), self.id());
        executor.runNext();
        List<VersionedEvent> events = new ArrayList<>();
        AsyncShopPurchaseMetrics metrics = new AsyncShopPurchaseMetrics();
        InMemoryPlayerCommandAuditLog audit = new InMemoryPlayerCommandAuditLog();
        PlayerGameAgent agent = new PlayerGameAgent(
                messages,
                self,
                new PlayerProfile(10001L, SERVER_OPEN_TIME),
                new FixedGameConfigView(runtime()),
                CLOCK,
                SERVER_OPEN_TIME,
                events::add,
                0,
                0,
                null,
                new RemoteShopStockAsyncClient(gameGateway),
                metrics,
                PlayerPushPort.NOOP,
                audit
        );
        agent.profile().bag().restore(new BagSnapshot(Map.of("gold", 100)));
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        long epoch = sessions.bind(10001L, "session-1").epoch();
        RecordingResultSink results = new RecordingResultSink();
        PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                sessions,
                new PlayerCommandSequencer(),
                new AdmissionControlledAgentRouter(
                        (target, operation) -> AdmissionDecision.accept(),
                        new LifecycleAwareAgentRouter(lifecycles, messages)
                ),
                audit,
                ignored -> 7,
                CLOCK
        );
        PlayerBusinessCommandBinder.registerExamples(
                dispatcher,
                new PlayerBusinessCommandHandler(playerId -> agent, results)
        );

        var dispatch = dispatcher.dispatch(new PlayerCommand(
                10001L,
                "session-1",
                epoch,
                1,
                PlayerBusinessOperations.SHOP_BUY,
                new BuyShopItemAsyncCommand("order-10001-rpc", "limited_pack", 1)
        ));
        executor.runNext();

        assertEquals(PlayerCommandStatus.ACCEPTED, dispatch.status());
        assertEquals(0, centerStocks.remaining("limited_pack"));
        assertEquals(0, results.envelopes.size());
        assertEquals(100, agent.profile().bag().count("gold"));
        assertEquals(0, agent.profile().bag().count("ticket"));

        executor.runNext();

        PlayerBusinessResponse envelope = results.envelopes.getFirst();
        ShopPurchaseResult result = assertInstanceOf(ShopPurchaseResult.class, envelope.payload());
        assertEquals(ShopPurchaseStatus.SUCCESS, result.status());
        assertEquals(50, agent.profile().bag().count("gold"));
        assertEquals(1, agent.profile().bag().count("ticket"));
        assertEquals(1, metrics.stats().reservedCallbacks());
        assertEquals(1, metrics.stats().completedPurchases());
        assertTrue(events.stream()
                .filter(PlayerDomainVersionedEvent.class::isInstance)
                .map(PlayerDomainVersionedEvent.class::cast)
                .anyMatch(event -> ShopItemPurchasedEvent.TYPE.equals(event.eventType())));
        assertEquals(PlayerCommandAuditOutcome.ASYNC_COMPLETED, audit.last().outcome());
        assertEquals(0, gameGateway.stats().pendingRequests());
    }

    @Test
    void successfulReservationIsReleasedWhenPlayerStateChangesBeforeCallback() {
        Fixture fixture = Fixture.create();
        fixture.agent.profile().bag().restore(new BagSnapshot(java.util.Map.of("gold", 100)));

        fixture.dispatch(new BuyShopItemAsyncCommand("order-10001-1", "limited_pack", 1));
        fixture.executor.runNext();
        fixture.agent.execute(execution -> execution.runtime().requireBagService()
                .consume(fixture.agent.profile().bag(), new ItemStack("gold", 80)));
        fixture.executor.runNext();

        fixture.stocks.reserveCallback.success(new ShopStockReserveResponse(true, 0));
        fixture.executor.runNext();

        ShopPurchaseResult result = assertInstanceOf(
                ShopPurchaseResult.class,
                fixture.results.envelopes.getFirst().payload()
        );
        assertEquals(ShopPurchaseStatus.NOT_ENOUGH_CURRENCY, result.status());
        assertEquals(20, fixture.agent.profile().bag().count("gold"));
        assertEquals(0, fixture.agent.profile().bag().count("ticket"));
        assertEquals(List.of("order-10001-1:limited_pack:1"), fixture.stocks.releases);
        AsyncShopPurchaseStats stats = fixture.metrics.stats();
        assertEquals(1, stats.reservedCallbacks());
        assertEquals(1, stats.rejectedPurchases());
        assertEquals(0, stats.releasedReservations());
        fixture.executor.runNext();
        stats = fixture.metrics.stats();
        assertEquals(1, stats.releasedReservations());
        assertEquals(PlayerCommandAuditOutcome.ASYNC_COMPLETED, fixture.audit.last().outcome());
        assertEquals("SHOP_NOT_ENOUGH_CURRENCY", fixture.audit.last().resultCode());
    }

    @Test
    void lateSuccessfulReservationAfterResponseTimeoutDoesNotMutatePlayerState() {
        Fixture fixture = Fixture.create();
        fixture.agent.profile().bag().restore(new BagSnapshot(java.util.Map.of("gold", 100)));
        InMemoryPlayerStateRepository repository = new InMemoryPlayerStateRepository();

        fixture.dispatch(new BuyShopItemAsyncCommand("order-10001-1", "limited_pack", 1));
        fixture.executor.runNext();
        fixture.results.accepting = false;
        fixture.agent.save(repository, snapshot -> {
        });
        fixture.executor.runNext();
        fixture.stocks.reserveCallback.success(new ShopStockReserveResponse(true, 0));
        fixture.executor.runNext();

        assertTrue(repository.load(10001L).isPresent());
        assertEquals(0, fixture.results.envelopes.size());
        assertEquals(100, fixture.agent.profile().bag().count("gold"));
        assertEquals(0, fixture.agent.profile().bag().count("ticket"));
        assertEquals(List.of("order-10001-1:limited_pack:1"), fixture.stocks.releases);
        assertEquals(0, fixture.events.size());
        AsyncShopPurchaseStats stats = fixture.metrics.stats();
        assertEquals(0, stats.currentPendingCommands());
        assertEquals(1, stats.abandonedPendingCommands());
        assertEquals(1, stats.lateCallbacks());
        assertEquals(0, stats.releasedReservations());
        fixture.executor.runNext();
        stats = fixture.metrics.stats();
        assertEquals(1, stats.releasedReservations());
        assertEquals(0, stats.completedPurchases());
        assertEquals(0, stats.rejectedPurchases());
    }

    @Test
    void releaseFailureAfterCompensationIsReportedFromPlayerMailbox() {
        Fixture fixture = Fixture.create();
        fixture.agent.profile().bag().restore(new BagSnapshot(java.util.Map.of("gold", 100)));
        fixture.stocks.releaseFailure = new IllegalStateException("release failed");

        fixture.dispatch(new BuyShopItemAsyncCommand("order-10001-1", "limited_pack", 1));
        fixture.executor.runNext();
        fixture.agent.execute(execution -> execution.runtime().requireBagService()
                .consume(fixture.agent.profile().bag(), new ItemStack("gold", 80)));
        fixture.executor.runNext();
        fixture.stocks.reserveCallback.success(new ShopStockReserveResponse(true, 0));
        fixture.executor.runNext();

        AsyncShopPurchaseStats beforeReleaseCallback = fixture.metrics.stats();
        assertEquals(1, beforeReleaseCallback.rejectedPurchases());
        assertEquals(0, beforeReleaseCallback.releaseFailures());

        fixture.executor.runNext();

        AsyncShopPurchaseStats afterReleaseCallback = fixture.metrics.stats();
        assertEquals(1, afterReleaseCallback.releaseFailures());
        assertEquals(0, afterReleaseCallback.releasedReservations());
    }

    @Test
    void outOfStockCallbackIsReportedAsRejectedAsyncPurchase() {
        Fixture fixture = Fixture.create();
        fixture.agent.profile().bag().restore(new BagSnapshot(java.util.Map.of("gold", 100)));

        fixture.dispatch(new BuyShopItemAsyncCommand("order-10001-1", "limited_pack", 1));
        fixture.executor.runNext();
        fixture.stocks.reserveCallback.success(new ShopStockReserveResponse(false, 0));
        fixture.executor.runNext();

        ShopPurchaseResult result = assertInstanceOf(
                ShopPurchaseResult.class,
                fixture.results.envelopes.getFirst().payload()
        );
        assertEquals(ShopPurchaseStatus.OUT_OF_STOCK, result.status());
        AsyncShopPurchaseStats stats = fixture.metrics.stats();
        assertEquals(1, stats.outOfStockCallbacks());
        assertEquals(1, stats.rejectedPurchases());
        assertEquals(0, stats.releasedReservations());
        assertEquals(PlayerCommandAuditOutcome.ASYNC_COMPLETED, fixture.audit.last().outcome());
        assertEquals("SHOP_OUT_OF_STOCK", fixture.audit.last().resultCode());
    }

    @Test
    void stockRpcFailureIsAuditedAsAsyncFailed() {
        Fixture fixture = Fixture.create();
        fixture.agent.profile().bag().restore(new BagSnapshot(java.util.Map.of("gold", 100)));

        fixture.dispatch(new BuyShopItemAsyncCommand("order-10001-1", "limited_pack", 1));
        fixture.executor.runNext();
        fixture.stocks.reserveCallback.failure(new IllegalStateException("rpc down"));
        fixture.executor.runNext();

        PlayerBusinessResponse envelope = fixture.results.envelopes.getFirst();
        assertEquals(PlayerBusinessResponseStatus.FAILED, envelope.status());
        assertEquals(PlayerBusinessResponse.BUSINESS_REJECTED, envelope.code());
        AsyncShopPurchaseStats stats = fixture.metrics.stats();
        assertEquals(1, stats.rpcFailures());
        assertEquals(PlayerCommandAuditOutcome.ASYNC_FAILED, fixture.audit.last().outcome());
        assertEquals(PlayerBusinessResponse.BUSINESS_REJECTED, fixture.audit.last().resultCode());
        assertEquals("rpc down", fixture.audit.last().reason());
    }

    @Test
    void synchronousStockRpcFailureClearsPendingAsyncCommand() {
        Fixture fixture = Fixture.create();
        fixture.agent.profile().bag().restore(new BagSnapshot(java.util.Map.of("gold", 100)));
        fixture.stocks.reserveFailure = new IllegalStateException("submit failed");
        InMemoryPlayerStateRepository repository = new InMemoryPlayerStateRepository();

        fixture.dispatch(new BuyShopItemAsyncCommand("order-10001-1", "limited_pack", 1));
        fixture.executor.runNext();
        fixture.agent.save(repository, snapshot -> {
        });
        fixture.executor.runNext();

        PlayerBusinessResponse envelope = fixture.results.envelopes.getFirst();
        assertEquals(PlayerBusinessResponseStatus.FAILED, envelope.status());
        assertEquals(PlayerBusinessResponse.BUSINESS_REJECTED, envelope.code());
        assertEquals("submit failed", envelope.message());
        assertEquals(1, fixture.metrics.stats().rpcFailures());
        assertEquals(0, fixture.metrics.stats().currentPendingCommands());
        assertTrue(repository.load(10001L).isPresent());
    }

    @Test
    void saveAndMigrationExportAreRejectedWhileAsyncPurchaseIsPending() {
        Fixture fixture = Fixture.create();
        fixture.agent.profile().bag().restore(new BagSnapshot(java.util.Map.of("gold", 100)));
        InMemoryPlayerStateRepository repository = new InMemoryPlayerStateRepository();
        AtomicReference<RuntimeException> saveFailure = new AtomicReference<>();

        fixture.dispatch(new BuyShopItemAsyncCommand("order-10001-1", "limited_pack", 1));
        fixture.executor.runNext();
        assertEquals(1, fixture.metrics.stats().currentPendingCommands());

        fixture.agent.save(repository, new PlayerStateSaveCallback() {
            @Override
            public void saved(PlayerStateSnapshot snapshot) {
                throw new AssertionError("pending async command must not be saved");
            }

            @Override
            public void failed(long playerId, RuntimeException error) {
                saveFailure.set(error);
            }
        });
        fixture.executor.runNext();

        assertInstanceOf(PendingAsyncPlayerCommandException.class, saveFailure.get());
        assertEquals("player 10001 has pending async commands: 1", saveFailure.get().getMessage());
        assertTrue(repository.load(10001L).isEmpty());
        assertEquals(1, fixture.metrics.stats().currentPendingCommands());

        AtomicReference<PlayerStateSnapshot> exported = new AtomicReference<>();
        fixture.agent.exportForMigration(exported::set);
        fixture.executor.runNext();
        assertNull(exported.get());

        fixture.stocks.reserveCallback.success(new ShopStockReserveResponse(true, 0));
        fixture.executor.runNext();
        fixture.agent.save(repository, snapshot -> {
        });
        fixture.executor.runNext();

        assertTrue(repository.load(10001L).isPresent());
        assertEquals(0, fixture.metrics.stats().currentPendingCommands());
        assertEquals(50, fixture.agent.profile().bag().count("gold"));
        assertEquals(1, fixture.agent.profile().bag().count("ticket"));
    }

    private record Fixture(
            RecordingExecutor executor,
            PlayerGameAgent agent,
            PlayerCommandDispatcher dispatcher,
            RecordingResultSink results,
            RecordingShopStockAsyncClient stocks,
            List<VersionedEvent> events,
            AsyncShopPurchaseMetrics metrics,
            InMemoryPlayerCommandAuditLog audit,
            long sessionEpoch
    ) {
        private static Fixture create() {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 1);
            ActorRef self = actors.actor("player-10001");
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
            lifecycles.activate(AgentIdentity.player(10001L), self.id());
            executor.runNext();
            RecordingShopStockAsyncClient stocks = new RecordingShopStockAsyncClient();
            List<VersionedEvent> events = new ArrayList<>();
            AsyncShopPurchaseMetrics metrics = new AsyncShopPurchaseMetrics();
            InMemoryPlayerCommandAuditLog audit = new InMemoryPlayerCommandAuditLog();
            PlayerGameAgent agent = new PlayerGameAgent(
                    messages,
                    self,
                    new PlayerProfile(10001L, SERVER_OPEN_TIME),
                    new FixedGameConfigView(runtime()),
                    CLOCK,
                    SERVER_OPEN_TIME,
                    events::add,
                    0,
                    0,
                    null,
                    stocks,
                    metrics,
                    PlayerPushPort.NOOP,
                    audit
            );
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            long epoch = sessions.bind(10001L, "session-1").epoch();
            RecordingResultSink results = new RecordingResultSink();
            PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                    sessions,
                    new PlayerCommandSequencer(),
                    new AdmissionControlledAgentRouter(
                            (target, operation) -> AdmissionDecision.accept(),
                            new LifecycleAwareAgentRouter(lifecycles, messages)
                    ),
                    audit,
                    ignored -> 7,
                    CLOCK
            );
            PlayerBusinessCommandBinder.registerExamples(
                    dispatcher,
                    new PlayerBusinessCommandHandler(playerId -> agent, results)
            );
            return new Fixture(executor, agent, dispatcher, results, stocks, events, metrics, audit, epoch);
        }

        private com.commonbattle.game.session.PlayerCommandResult dispatch(PlayerBusinessCommand<?> payload) {
            return dispatcher.dispatch(new PlayerCommand(
                    10001L,
                    "session-1",
                    sessionEpoch,
                    results.envelopes.size() + 1L,
                    payload.operation(),
                    payload
            ));
        }
    }

    private static GameConfigRuntime runtime() {
        return GameConfigRuntime.from(new GameConfigPackage(
                7,
                List.of(
                        new ItemDefinition("gold", "currency", 999_999),
                        new ItemDefinition("ticket", "ticket", 999_999)
                ),
                List.<ActivityDefinition>of(),
                List.of(new ShopItemDefinition(
                        "limited_pack",
                        new ItemStack("gold", 50),
                        Reward.of(new ItemStack("ticket", 1)),
                        0,
                        0,
                        1
                )),
                new GrowthTuning("gold", 1, 100),
                CLOCK.instant()
        ));
    }

    private static ServiceDescriptor descriptor(
            ServiceKind kind,
            String node,
            int port,
            Set<String> topics
    ) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                topics,
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

    private static final class RecordingShopStockAsyncClient implements ShopStockAsyncClient {
        private String reserveReservationId;
        private String reserveSku;
        private ShopStockCallback<ShopStockReserveResponse> reserveCallback;
        private RuntimeException reserveFailure;
        private RuntimeException releaseFailure;
        private final List<String> releases = new ArrayList<>();

        @Override
        public void reserve(
                String reservationId,
                String sku,
                int count,
                ShopStockCallback<ShopStockReserveResponse> callback
        ) {
            if (reserveFailure != null) {
                throw reserveFailure;
            }
            reserveReservationId = reservationId;
            reserveSku = sku;
            reserveCallback = callback;
        }

        @Override
        public void release(
                String reservationId,
                String sku,
                int count,
                ShopStockCallback<ShopStockReleaseResponse> callback
        ) {
            releases.add(reservationId + ":" + sku + ":" + count);
            if (releaseFailure != null) {
                callback.failure(releaseFailure);
                return;
            }
            callback.success(new ShopStockReleaseResponse(1));
        }

        @Override
        public void remaining(String sku, ShopStockCallback<com.commonbattle.game.shop.ShopStockRemainingResponse> callback) {
        }
    }

    private static final class RecordingResultSink implements PlayerBusinessResultSink {
        private final List<PlayerBusinessResponse> envelopes = new ArrayList<>();
        private boolean accepting = true;

        @Override
        public boolean canComplete(PlayerCommand command) {
            return accepting;
        }

        @Override
        public void completed(PlayerCommand command, PlayerBusinessResponse response) {
            envelopes.add(response);
        }

        @Override
        public void succeeded(PlayerCommand command, Object response) {
        }

        @Override
        public void failed(PlayerCommand command, Throwable error) {
            envelopes.add(PlayerBusinessResponse.failure(command, error));
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        private void runNext() {
            commands.removeFirst().run();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
