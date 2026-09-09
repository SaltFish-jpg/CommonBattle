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
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
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
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerCommandStatus;
import com.commonbattle.game.shop.ShopItemDefinition;
import com.commonbattle.game.shop.ShopPurchaseResult;
import com.commonbattle.game.shop.ShopPurchaseStatus;
import com.commonbattle.game.shop.ShopStockAsyncClient;
import com.commonbattle.game.shop.ShopStockCallback;
import com.commonbattle.game.shop.ShopStockReleaseResponse;
import com.commonbattle.game.shop.ShopStockReserveResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        assertEquals(1, stats.reservedCallbacks());
        assertEquals(1, stats.completedPurchases());
        assertEquals(0, stats.releasedReservations());
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
        assertEquals(1, stats.releasedReservations());
    }

    @Test
    void lateSuccessfulReservationAfterResponseTimeoutDoesNotMutatePlayerState() {
        Fixture fixture = Fixture.create();
        fixture.agent.profile().bag().restore(new BagSnapshot(java.util.Map.of("gold", 100)));

        fixture.dispatch(new BuyShopItemAsyncCommand("order-10001-1", "limited_pack", 1));
        fixture.executor.runNext();
        fixture.results.accepting = false;
        fixture.stocks.reserveCallback.success(new ShopStockReserveResponse(true, 0));
        fixture.executor.runNext();

        assertEquals(0, fixture.results.envelopes.size());
        assertEquals(100, fixture.agent.profile().bag().count("gold"));
        assertEquals(0, fixture.agent.profile().bag().count("ticket"));
        assertEquals(List.of("order-10001-1:limited_pack:1"), fixture.stocks.releases);
        assertEquals(0, fixture.events.size());
        AsyncShopPurchaseStats stats = fixture.metrics.stats();
        assertEquals(1, stats.lateCallbacks());
        assertEquals(1, stats.releasedReservations());
        assertEquals(0, stats.completedPurchases());
        assertEquals(0, stats.rejectedPurchases());
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
    }

    private record Fixture(
            RecordingExecutor executor,
            PlayerGameAgent agent,
            PlayerCommandDispatcher dispatcher,
            RecordingResultSink results,
            RecordingShopStockAsyncClient stocks,
            List<VersionedEvent> events,
            AsyncShopPurchaseMetrics metrics,
            long sessionEpoch
    ) {
        private static Fixture create() {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
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
                    metrics
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
                    new InMemoryPlayerCommandAuditLog(),
                    ignored -> 7,
                    CLOCK
            );
            PlayerBusinessCommandBinder.registerExamples(
                    dispatcher,
                    new PlayerBusinessCommandHandler(playerId -> agent, results)
            );
            return new Fixture(executor, agent, dispatcher, results, stocks, events, metrics, epoch);
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
        private final List<String> releases = new ArrayList<>();

        @Override
        public void reserve(
                String reservationId,
                String sku,
                int count,
                ShopStockCallback<ShopStockReserveResponse> callback
        ) {
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
