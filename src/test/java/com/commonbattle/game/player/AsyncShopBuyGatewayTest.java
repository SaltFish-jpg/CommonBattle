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
import com.commonbattle.game.session.InMemoryPlayerCommandAuditLog;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.shop.ShopItemDefinition;
import com.commonbattle.game.shop.ShopPurchaseResult;
import com.commonbattle.game.shop.ShopPurchaseStatus;
import com.commonbattle.game.shop.ShopStockAsyncClient;
import com.commonbattle.game.shop.ShopStockCallback;
import com.commonbattle.game.shop.ShopStockReleaseResponse;
import com.commonbattle.game.shop.ShopStockReserveResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncShopBuyGatewayTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant SERVER_OPEN_TIME = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void gatewayCompletesAsyncShopBuyOnlyAfterStockCallbackReturnsToPlayerMailbox() {
        Fixture fixture = Fixture.create(PlayerBusinessCommandGateway.DEFAULT_RESPONSE_TIMEOUT);
        try {
            fixture.agent.profile().bag().restore(new BagSnapshot(java.util.Map.of("gold", 100)));
            RecordingCallback callback = new RecordingCallback();

            fixture.gateway.submit(command(new BuyShopItemAsyncCommand("order-10001-1", "limited_pack", 1)), callback);

            assertNull(callback.response.get());
            assertEquals(1, fixture.responses.pendingResponses());
            fixture.executor.runNext();
            assertNull(callback.response.get());
            assertEquals(100, fixture.agent.profile().bag().count("gold"));

            fixture.stocks.reserveCallback.success(new ShopStockReserveResponse(true, 0));
            assertNull(callback.response.get());
            assertEquals(100, fixture.agent.profile().bag().count("gold"));
            fixture.executor.runNext();

            PlayerBusinessResponse envelope = callback.response.get();
            ShopPurchaseResult result = assertInstanceOf(ShopPurchaseResult.class, envelope.payload());
            assertEquals(PlayerBusinessResponseStatus.SUCCESS, envelope.status());
            assertEquals(ShopPurchaseStatus.SUCCESS, result.status());
            assertEquals(0, fixture.responses.pendingResponses());
            assertEquals(50, fixture.agent.profile().bag().count("gold"));
            assertEquals(1, fixture.agent.profile().bag().count("ticket"));
        } finally {
            fixture.gateway.close();
        }
    }

    @Test
    void gatewayTimeoutPreventsLateStockCallbackFromMutatingPlayerState() throws Exception {
        Fixture fixture = Fixture.create(Duration.ofMillis(10));
        try {
            fixture.agent.profile().bag().restore(new BagSnapshot(java.util.Map.of("gold", 100)));
            RecordingCallback callback = new RecordingCallback();

            fixture.gateway.submit(command(new BuyShopItemAsyncCommand("order-10001-1", "limited_pack", 1)), callback);
            fixture.executor.runNext();

            assertTrue(await(() -> callback.response.get() != null));
            assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
            assertEquals(PlayerBusinessResponse.TIMEOUT, callback.response.get().code());
            assertEquals(0, fixture.responses.pendingResponses());

            fixture.stocks.reserveCallback.success(new ShopStockReserveResponse(true, 0));
            fixture.executor.runNext();

            assertEquals(PlayerBusinessResponse.TIMEOUT, callback.response.get().code());
            assertEquals(100, fixture.agent.profile().bag().count("gold"));
            assertEquals(0, fixture.agent.profile().bag().count("ticket"));
            assertEquals(List.of("order-10001-1:limited_pack:1"), fixture.stocks.releases);
        } finally {
            fixture.gateway.close();
        }
    }

    private static PlayerCommand command(PlayerBusinessCommand<?> payload) {
        return new PlayerCommand(
                10001L,
                "session-1",
                1,
                1,
                payload.operation(),
                payload
        );
    }

    private record Fixture(
            RecordingExecutor executor,
            PlayerGameAgent agent,
            PlayerBusinessResponseHub responses,
            PlayerBusinessCommandGateway gateway,
            RecordingShopStockAsyncClient stocks
    ) {
        private static Fixture create(Duration timeout) {
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
            PlayerGameAgent agent = new PlayerGameAgent(
                    messages,
                    self,
                    new PlayerProfile(10001L, SERVER_OPEN_TIME),
                    new FixedGameConfigView(runtime()),
                    CLOCK,
                    SERVER_OPEN_TIME,
                    ignored -> {
                    },
                    0,
                    0,
                    null,
                    stocks
            );
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            sessions.bind(10001L, "session-1");
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
            PlayerBusinessResponseHub responses = new PlayerBusinessResponseHub();
            PlayerBusinessCommandBinder.registerExamples(
                    dispatcher,
                    new PlayerBusinessCommandHandler(playerId -> agent, responses)
            );
            PlayerBusinessCommandGateway gateway = new PlayerBusinessCommandGateway(
                    dispatcher,
                    new NoopRpcGateway(),
                    responses,
                    timeout
            );
            return new Fixture(executor, agent, responses, gateway, stocks);
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
        private ShopStockCallback<ShopStockReserveResponse> reserveCallback;
        private final List<String> releases = new ArrayList<>();

        @Override
        public void reserve(
                String reservationId,
                String sku,
                int count,
                ShopStockCallback<ShopStockReserveResponse> callback
        ) {
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

    private static final class RecordingCallback implements RpcCallback<PlayerBusinessResponse> {
        private final AtomicReference<PlayerBusinessResponse> response = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public void success(PlayerBusinessResponse response) {
            this.response.set(response);
        }

        @Override
        public void failure(Throwable error) {
            failure.set(error);
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

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
