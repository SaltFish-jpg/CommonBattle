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
import com.commonbattle.game.activity.ActivityCatalog;
import com.commonbattle.game.activity.ActivityClaimResult;
import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.activity.ActivityService;
import com.commonbattle.game.activity.ActivityType;
import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemCatalog;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.battle.BattleService;
import com.commonbattle.game.battle.BattleSettlementResult;
import com.commonbattle.game.battle.BattleSettlementStatus;
import com.commonbattle.game.battle.BattleStageCatalog;
import com.commonbattle.game.battle.BattleStageDefinition;
import com.commonbattle.game.growth.GrowthResult;
import com.commonbattle.game.growth.GrowthService;
import com.commonbattle.game.growth.GrowthSnapshot;
import com.commonbattle.game.session.InMemoryPlayerCommandAuditLog;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandAuditOutcome;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandResult;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerCommandStatus;
import com.commonbattle.game.shop.ShopCatalog;
import com.commonbattle.game.shop.ShopItemDefinition;
import com.commonbattle.game.shop.ShopPurchaseResult;
import com.commonbattle.game.shop.ShopPurchaseStatus;
import com.commonbattle.game.shop.ShopService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerBusinessCommandHandlerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void activityGrowthAndShopCommandsRunThroughPlayerCommandDispatcher() {
        Fixture fixture = Fixture.create();
        fixture.agent.profile().bag().restore(new com.commonbattle.game.bag.BagSnapshot(java.util.Map.of(
                "gold", 100,
                "exp_potion", 2
        )));

        PlayerCommandResult progress = fixture.dispatch(new ActivityProgressCommand("kill-3", 3));
        PlayerCommandResult claim = fixture.dispatch(new ClaimActivityCommand("kill-3"));
        PlayerCommandResult growth = fixture.dispatch(new UseExpItemsCommand(1));
        PlayerCommandResult shop = fixture.dispatch(new BuyShopItemCommand("growth_pack", 1));
        PlayerCommandResult battle = fixture.dispatch(new BattleStageClearCommand("settle-10001-1", "forest-1"));
        PlayerCommandResult sweep = fixture.dispatch(new BattleStageSweepCommand("sweep-10001-1", "forest-1"));

        assertEquals(PlayerCommandStatus.ACCEPTED, progress.status());
        assertEquals(PlayerCommandStatus.ACCEPTED, claim.status());
        assertEquals(PlayerCommandStatus.ACCEPTED, growth.status());
        assertEquals(PlayerCommandStatus.ACCEPTED, shop.status());
        assertEquals(PlayerCommandStatus.ACCEPTED, battle.status());
        assertEquals(PlayerCommandStatus.ACCEPTED, sweep.status());
        assertEquals(1, fixture.executor.queued());
        fixture.executor.runAll();

        assertEquals(PlayerBusinessAck.OK, fixture.results.responses.get(0));
        assertInstanceOf(ActivityClaimResult.class, fixture.results.responses.get(1));
        assertInstanceOf(GrowthResult.class, fixture.results.responses.get(2));
        ShopPurchaseResult purchase = assertInstanceOf(ShopPurchaseResult.class, fixture.results.responses.get(3));
        BattleSettlementResult settlement = assertInstanceOf(BattleSettlementResult.class, fixture.results.responses.get(4));
        BattleSettlementResult sweepSettlement = assertInstanceOf(BattleSettlementResult.class, fixture.results.responses.get(5));
        assertEquals(ShopPurchaseStatus.SUCCESS, purchase.status());
        assertEquals(BattleSettlementStatus.VICTORY, settlement.status());
        assertTrue(sweepSettlement.swept());
        assertEquals(110, fixture.agent.profile().bag().count("gold"));
        assertEquals(4, fixture.agent.profile().bag().count("exp_potion"));
        assertEquals(10, fixture.agent.profile().bag().count("gem"));
        assertEquals(2, fixture.agent.profile().activities().progress("battle-win-1").value());
        assertEquals(1, fixture.agent.profile().shop().lifetimePurchased("growth_pack"));
        assertEquals(110, fixture.agent.profile().growth().stamina());
        assertEquals(6, fixture.audit.records().size());
        assertEquals(PlayerCommandAuditOutcome.EXECUTED, fixture.audit.last().outcome());
        assertEquals(7, fixture.audit.last().configVersion());
    }

    @Test
    void unifiedBusinessResponseEnvelopeKeepsCommandMetadataAndPayload() {
        RecordingUnifiedResultSink results = new RecordingUnifiedResultSink();
        Fixture fixture = Fixture.create(results);

        PlayerCommandResult progress = fixture.dispatch(new ActivityProgressCommand("kill-3", 1));
        fixture.executor.runAll();

        assertEquals(PlayerCommandStatus.ACCEPTED, progress.status());
        PlayerBusinessResponse response = results.envelopes.getFirst();
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, response.status());
        assertEquals(PlayerBusinessResponse.OK, response.code());
        assertEquals(10001L, response.playerId());
        assertEquals("session-1", response.sessionId());
        assertEquals(1, response.sessionEpoch());
        assertEquals(1, response.sequence());
        assertEquals(PlayerBusinessOperations.ACTIVITY_PROGRESS, response.operation());
        assertEquals(PlayerBusinessAck.OK, response.payload());
        assertEquals(PlayerBusinessAck.OK, results.responses.getFirst());
    }

    @Test
    void operationMismatchIsReportedAsMailboxExecutionFailure() {
        Fixture fixture = Fixture.create();
        PlayerCommand command = fixture.command(PlayerBusinessOperations.SHOP_BUY, 1, new ClaimActivityCommand("kill-3"));

        PlayerCommandResult accepted = fixture.dispatcher.dispatch(command);

        assertEquals(PlayerCommandStatus.ACCEPTED, accepted.status());
        fixture.executor.runNext();
        assertEquals(1, fixture.results.failures.size());
        assertEquals("player command operation does not match payload", fixture.results.failures.getFirst().getMessage());
        assertEquals(PlayerCommandAuditOutcome.FAILED, fixture.audit.last().outcome());
    }

    @Test
    void unifiedBusinessResponseEnvelopeReportsFailuresWithStableCode() {
        RecordingUnifiedResultSink results = new RecordingUnifiedResultSink();
        Fixture fixture = Fixture.create(results);
        PlayerCommand command = fixture.command(PlayerBusinessOperations.SHOP_BUY, 1, new ClaimActivityCommand("kill-3"));

        fixture.dispatcher.dispatch(command);
        fixture.executor.runNext();

        PlayerBusinessResponse response = results.envelopes.getFirst();
        assertEquals(PlayerBusinessResponseStatus.FAILED, response.status());
        assertEquals(PlayerBusinessResponse.BAD_REQUEST, response.code());
        assertEquals("player command operation does not match payload", response.message());
        assertEquals(null, response.payload());
        PlayerBusinessResponseException error = assertInstanceOf(
                PlayerBusinessResponseException.class,
                results.failures.getFirst()
        );
        assertEquals(response, error.response());
    }

    @Test
    void battleCommandReportsBusinessRejectedWhenStaminaIsNotEnough() {
        RecordingUnifiedResultSink results = new RecordingUnifiedResultSink();
        Fixture fixture = Fixture.create(results);
        fixture.agent.profile().growth().restore(new GrowthSnapshot(
                1,
                0,
                3,
                GrowthSnapshot.DEFAULT_MAX_STAMINA,
                CLOCK.instant()
        ));

        fixture.dispatch(new BattleStageClearCommand("settle-10001-1", "forest-1"));
        fixture.executor.runNext();

        PlayerBusinessResponse response = results.envelopes.getFirst();
        assertEquals(PlayerBusinessResponseStatus.FAILED, response.status());
        assertEquals(PlayerBusinessResponse.BUSINESS_REJECTED, response.code());
        assertTrue(response.message().contains("Not enough stamina"));
        assertEquals(0, fixture.agent.profile().bag().count("gold"));
        assertEquals(3, fixture.agent.profile().growth().stamina());
        assertEquals(PlayerCommandAuditOutcome.FAILED, fixture.audit.last().outcome());
    }

    private record Fixture(
            RecordingExecutor executor,
            PlayerGameAgent agent,
            PlayerCommandDispatcher dispatcher,
            RecordingResultSink results,
            InMemoryPlayerCommandAuditLog audit,
            AtomicLong nextSequence
    ) {
        private static Fixture create() {
            return create(new RecordingResultSink());
        }

        private static Fixture create(RecordingResultSink results) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            ActorRef self = actors.actor("player-10001");
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
            lifecycles.activate(AgentIdentity.player(10001L), self.id());
            executor.runNext();
            PlayerGameAgent agent = new PlayerGameAgent(
                    new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                    self,
                    new PlayerProfile(10001L, Instant.parse("2026-08-01T00:00:00Z")),
                    activityService(),
                    growthService(),
                    shopService(),
                    battleService(),
                    CLOCK,
                    Instant.parse("2026-08-01T00:00:00Z")
            );
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            sessions.bind(10001L, "session-1");
            InMemoryPlayerCommandAuditLog audit = new InMemoryPlayerCommandAuditLog();
            PlayerBusinessCommandHandler handler = new PlayerBusinessCommandHandler(playerId -> agent, results);
            PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                    sessions,
                    new PlayerCommandSequencer(),
                    new AdmissionControlledAgentRouter(
                            (target, operation) -> AdmissionDecision.accept(),
                            new LifecycleAwareAgentRouter(lifecycles, new DefaultAgentMessagePort(actors, new NoopRpcGateway()))
                    ),
                    audit,
                    ignored -> 7,
                    CLOCK
            );
            PlayerBusinessCommandBinder.registerExamples(dispatcher, handler);
            return new Fixture(executor, agent, dispatcher, results, audit, new AtomicLong());
        }

        private PlayerCommandResult dispatch(PlayerBusinessCommand<?> payload) {
            return dispatcher.dispatch(command(payload.operation(), nextSequence.incrementAndGet(), payload));
        }

        private PlayerCommand command(String operation, long sequence, Object payload) {
            return new PlayerCommand(10001L, "session-1", 1, sequence, operation, payload);
        }
    }

    private static ActivityService activityService() {
        ActivityCatalog activities = new ActivityCatalog();
        activities.register(new ActivityDefinition(
                "kill-3",
                ActivityType.COUNTER,
                3,
                Reward.of(new ItemStack("gem", 5))
        ));
        activities.register(new ActivityDefinition(
                "battle-win-1",
                ActivityType.COUNTER,
                1,
                Reward.of(new ItemStack("gem", 2))
        ));
        return new ActivityService(activities, bagService());
    }

    private static GrowthService growthService() {
        return new GrowthService(bagService(), "exp_potion", 60, 100);
    }

    private static ShopService shopService() {
        ShopCatalog shops = new ShopCatalog();
        shops.register(new ShopItemDefinition(
                "growth_pack",
                new ItemStack("gold", 50),
                Reward.of(new ItemStack("exp_potion", 1)),
                2,
                1,
                ShopItemDefinition.UNLIMITED_STOCK
        ));
        return new ShopService(shops, bagService(), CLOCK, ZoneOffset.UTC);
    }

    private static BattleService battleService() {
        BattleStageCatalog battles = new BattleStageCatalog();
        battles.register(new BattleStageDefinition(
                "forest-1",
                100,
                40,
                70,
                8,
                5,
                Reward.of(new ItemStack("gold", 30), new ItemStack("exp_potion", 1)),
                "battle-win-1",
                1,
                Reward.of(new ItemStack("gem", 5)),
                3,
                5
        ));
        return new BattleService(battles, bagService(), activityService());
    }

    private static BagService bagService() {
        ItemCatalog items = new ItemCatalog();
        items.register(new ItemDefinition("gold", "currency", 999999));
        items.register(new ItemDefinition("exp_potion", "growth", 999));
        items.register(new ItemDefinition("gem", "currency", 999999));
        return new BagService(items);
    }

    private static class RecordingResultSink implements PlayerBusinessResultSink {
        final List<Object> responses = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();

        @Override
        public void succeeded(PlayerCommand command, Object response) {
            responses.add(response);
        }

        @Override
        public void failed(PlayerCommand command, Throwable error) {
            failures.add(error);
        }
    }

    private static final class RecordingUnifiedResultSink extends RecordingResultSink {
        private final List<PlayerBusinessResponse> envelopes = new ArrayList<>();

        @Override
        public void completed(PlayerCommand command, PlayerBusinessResponse response) {
            envelopes.add(response);
            if (response.succeeded()) {
                succeeded(command, response.payload());
            } else {
                failed(command, new PlayerBusinessResponseException(response));
            }
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        int queued() {
            return commands.size();
        }

        void runNext() {
            commands.removeFirst().run();
        }

        void runAll() {
            while (!commands.isEmpty()) {
                runNext();
            }
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
