package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorScheduleRegistry;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.activity.ActivityCatalog;
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
import com.commonbattle.game.growth.GrowthRecoveryResult;
import com.commonbattle.game.growth.GrowthResult;
import com.commonbattle.game.growth.GrowthService;
import com.commonbattle.game.growth.GrowthSnapshot;
import com.commonbattle.game.shop.InMemoryShopOrderRepository;
import com.commonbattle.game.shop.PlayerShopState;
import com.commonbattle.game.shop.ShopCatalog;
import com.commonbattle.game.shop.ShopItemDefinition;
import com.commonbattle.game.shop.ShopPurchaseResult;
import com.commonbattle.game.shop.ShopPurchaseStatus;
import com.commonbattle.game.shop.ShopService;
import com.commonbattle.game.session.PlayerOutboundDeliveryResult;
import com.commonbattle.game.session.PlayerOutboundTopicPolicies;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerGameAgentTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void loginActivityGrantsRewardIntoBagThroughPlayerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        PlayerGameAgent agent = createAgent(executor);
        AtomicReference<Integer> goldAfterClaim = new AtomicReference<>();

        agent.onLogin("daily-login", result -> goldAfterClaim.set(
                result.bagResult().changes().getFirst().after()
        ));
        executor.runNext();

        assertEquals(100, goldAfterClaim.get());
        assertEquals(100, agent.profile().bag().count("gold"));
    }

    @Test
    void loginActivityPushesFreshSnapshotsAfterMailboxMutation() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingPushPort pushes = new RecordingPushPort();
        PlayerGameAgent agent = createAgent(executor, CLOCK, Instant.EPOCH, pushes);

        agent.onLogin("daily-login", ignored -> {
        });

        assertEquals(List.of(), pushes.messages);
        executor.runNext();
        assertEquals(2, pushes.messages.size());
        assertEquals(PlayerOutboundTopicPolicies.ACTIVITY_PROGRESS, pushes.messages.get(0).topic());
        assertEquals(PlayerOutboundTopicPolicies.BAG_SNAPSHOT, pushes.messages.get(1).topic());
        PlayerPushPayloads.ActivityProgressPayload activities = assertInstanceOf(
                PlayerPushPayloads.ActivityProgressPayload.class,
                pushes.messages.get(0).payload()
        );
        PlayerPushPayloads.BagSnapshotPayload bag = assertInstanceOf(
                PlayerPushPayloads.BagSnapshotPayload.class,
                pushes.messages.get(1).payload()
        );
        assertEquals(1, activities.progress.get("daily-login").value);
        assertEquals(100, bag.itemCounts.get("gold"));
        assertEquals(2, bag.itemCounts.get("exp_potion"));
    }

    @Test
    void scheduledActivityRefreshPushesSnapshotAfterMailboxExecution() throws InterruptedException {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        RecordingPushPort pushes = new RecordingPushPort();
        PlayerGameAgent agent = createAgent(actors, executor, CLOCK, Instant.EPOCH, pushes);

        agent.addActivityProgress("kill-3", 2);
        executor.runNext();
        pushes.messages.clear();

        try (ActorScheduleRegistry schedules = new ActorScheduleRegistry(actors)) {
            agent.scheduleActivitySnapshotRefresh(schedules, Duration.ofMillis(1), Duration.ofMillis(100));

            assertTrue(awaitQueued(executor, 1));
            assertEquals(List.of(), pushes.messages);

            executor.runNext();

            assertEquals(1, pushes.messages.size());
            assertEquals(PlayerOutboundTopicPolicies.ACTIVITY_PROGRESS, pushes.messages.getFirst().topic());
            PlayerPushPayloads.ActivityProgressPayload activities = assertInstanceOf(
                    PlayerPushPayloads.ActivityProgressPayload.class,
                    pushes.messages.getFirst().payload()
            );
            assertEquals(2, activities.progress.get("kill-3").value);
            assertEquals(1, schedules.stats().deliveredTimerMessages());
        }
    }

    @Test
    void growthStaminaRecoveryRunsInsidePlayerMailboxAndPushesSnapshot() throws InterruptedException {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        RecordingPushPort pushes = new RecordingPushPort();
        PlayerGameAgent agent = createAgent(actors, executor, CLOCK, Instant.EPOCH, pushes);
        agent.profile().growth().restore(new GrowthSnapshot(
                1,
                0,
                0,
                GrowthSnapshot.DEFAULT_MAX_STAMINA,
                CLOCK.instant().minus(Duration.ofMinutes(10))
        ));

        try (ActorScheduleRegistry schedules = new ActorScheduleRegistry(actors)) {
            agent.scheduleGrowthStaminaRecovery(schedules, Duration.ofMillis(1), Duration.ofMinutes(1));

            assertTrue(awaitQueued(executor, 1));
            assertEquals(List.of(), pushes.messages);
            assertEquals(0, agent.profile().growth().stamina());

            executor.runNext();

            assertEquals(2, agent.profile().growth().stamina());
            assertEquals(1, pushes.messages.size());
            assertEquals(PlayerOutboundTopicPolicies.GROWTH_SNAPSHOT, pushes.messages.getFirst().topic());
            PlayerPushPayloads.GrowthSnapshotPayload growth = assertInstanceOf(
                    PlayerPushPayloads.GrowthSnapshotPayload.class,
                    pushes.messages.getFirst().payload()
            );
            assertEquals(2, growth.stamina);
            assertEquals(GrowthSnapshot.DEFAULT_MAX_STAMINA, growth.maxStamina);
        }
    }

    @Test
    void directGrowthStaminaRecoveryUsesPlayerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingPushPort pushes = new RecordingPushPort();
        PlayerGameAgent agent = createAgent(executor, CLOCK, Instant.EPOCH, pushes);
        agent.profile().growth().restore(new GrowthSnapshot(
                1,
                0,
                1,
                GrowthSnapshot.DEFAULT_MAX_STAMINA,
                CLOCK.instant().minus(Duration.ofMinutes(5))
        ));
        AtomicReference<GrowthRecoveryResult> result = new AtomicReference<>();

        agent.recoverGrowthStamina(result::set);

        assertEquals(1, agent.profile().growth().stamina());
        executor.runNext();
        assertEquals(2, result.get().afterStamina());
        assertEquals(2, agent.profile().growth().stamina());
        assertEquals(PlayerOutboundTopicPolicies.GROWTH_SNAPSHOT, pushes.messages.getFirst().topic());
    }

    @Test
    void expItemConsumptionUsesBagThenLevelsGrowth() {
        RecordingExecutor executor = new RecordingExecutor();
        PlayerGameAgent agent = createAgent(executor);
        agent.onLogin("daily-login", ignored -> {
        });
        executor.runNext();
        AtomicReference<GrowthResult> growth = new AtomicReference<>();

        agent.useExpItems(2, growth::set);
        executor.runNext();

        assertEquals(1, growth.get().beforeLevel());
        assertEquals(2, growth.get().afterLevel());
        assertEquals(0, agent.profile().bag().count("exp_potion"));
        assertEquals(20, agent.profile().growth().exp());
    }

    @Test
    void activityProgressRewardCanOnlyClaimOnce() {
        RecordingExecutor executor = new RecordingExecutor();
        PlayerGameAgent agent = createAgent(executor);

        agent.addActivityProgress("kill-3", 3);
        executor.runNext();
        agent.claimActivity("kill-3", ignored -> {
        });
        executor.runNext();

        assertEquals(5, agent.profile().bag().count("gem"));
        agent.claimActivity("kill-3", ignored -> {
        });
        executor.runNext();
        assertEquals(5, agent.profile().bag().count("gem"));
    }

    @Test
    void playerAgentPassesClockAndServerOpenTimeToActivity() {
        RecordingExecutor executor = new RecordingExecutor();
        Instant serverOpen = Instant.parse("2026-08-01T00:00:00Z");
        Clock clock = Clock.fixed(Instant.parse("2026-08-02T12:00:00Z"), ZoneOffset.UTC);
        PlayerGameAgent agent = createAgent(executor, clock, serverOpen);

        agent.addActivityProgress("open-day-2", 1);
        executor.runNext();
        agent.claimActivity("open-day-2", ignored -> {
        });
        executor.runNext();

        assertEquals(2, agent.profile().bag().count("gem"));
    }

    @Test
    void shopPurchaseRunsInsidePlayerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        PlayerGameAgent agent = createShopAgent(executor);
        AtomicReference<ShopPurchaseResult> purchase = new AtomicReference<>();
        agent.onLogin("daily-login", ignored -> {
        });
        executor.runNext();

        agent.buyShopItem("growth_pack", 1, purchase::set);

        assertEquals(100, agent.profile().bag().count("gold"));
        executor.runNext();
        assertEquals(ShopPurchaseStatus.SUCCESS, purchase.get().status());
        assertEquals(50, agent.profile().bag().count("gold"));
        assertEquals(1, agent.profile().bag().count("exp_potion"));
        assertEquals(1, agent.profile().shop().lifetimePurchased("growth_pack"));
    }

    @Test
    void duplicateShopOrderReplaysInsidePlayerMailboxWithoutDoubleMutation() {
        RecordingExecutor executor = new RecordingExecutor();
        PlayerGameAgent agent = createShopAgent(executor);
        AtomicReference<ShopPurchaseResult> first = new AtomicReference<>();
        AtomicReference<ShopPurchaseResult> replay = new AtomicReference<>();
        agent.onLogin("daily-login", ignored -> {
        });
        executor.runNext();

        agent.buyShopItem("order-10001-1", "growth_pack", 1, first::set);
        agent.buyShopItem("order-10001-1", "growth_pack", 1, replay::set);
        executor.runNext();

        assertEquals(ShopPurchaseStatus.SUCCESS, first.get().status());
        assertEquals(ShopPurchaseStatus.SUCCESS, replay.get().status());
        assertTrue(replay.get().replayed());
        assertEquals(50, agent.profile().bag().count("gold"));
        assertEquals(1, agent.profile().bag().count("exp_potion"));
        assertEquals(1, agent.profile().shop().lifetimePurchased("growth_pack"));
    }

    @Test
    void battleStageClearRunsInsidePlayerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        PlayerGameAgent agent = createBattleAgent(executor);
        AtomicReference<BattleSettlementResult> settlement = new AtomicReference<>();

        agent.clearBattleStage("forest-1", settlement::set);

        assertEquals(0, agent.profile().bag().count("gold"));
        executor.runNext();
        assertEquals(BattleSettlementStatus.VICTORY, settlement.get().status());
        assertEquals(115, agent.profile().growth().stamina());
        assertEquals(30, agent.profile().bag().count("gold"));
        assertEquals(5, agent.profile().bag().count("gem"));
        assertEquals(1, agent.profile().activities().progress("battle-win-1").value());
    }

    @Test
    void battleStageClearRejectsBeforeSettlementWhenStaminaIsNotEnough() {
        RecordingExecutor executor = new RecordingExecutor();
        PlayerGameAgent agent = createBattleAgent(executor);
        AtomicReference<BattleSettlementResult> settlement = new AtomicReference<>();
        agent.profile().growth().restore(new GrowthSnapshot(
                1,
                0,
                3,
                GrowthSnapshot.DEFAULT_MAX_STAMINA,
                CLOCK.instant()
        ));

        agent.clearBattleStage("settle-10001-1", "forest-1", settlement::set);

        executor.runNext();

        assertEquals(3, agent.profile().growth().stamina());
        assertEquals(0, agent.profile().bag().count("gold"));
        assertTrue(agent.profile().battle().replay("settle-10001-1", "forest-1").isEmpty());
        assertNull(settlement.get());
    }

    @Test
    void duplicateBattleSettlementReplaysInsidePlayerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        PlayerGameAgent agent = createBattleAgent(executor);
        AtomicReference<BattleSettlementResult> first = new AtomicReference<>();
        AtomicReference<BattleSettlementResult> replay = new AtomicReference<>();

        agent.clearBattleStage("settle-10001-1", "forest-1", first::set);
        agent.clearBattleStage("settle-10001-1", "forest-1", replay::set);
        executor.runNext();

        assertEquals(BattleSettlementStatus.VICTORY, first.get().status());
        assertTrue(replay.get().replayed());
        assertEquals(115, agent.profile().growth().stamina());
        assertEquals(30, agent.profile().bag().count("gold"));
        assertEquals(5, agent.profile().bag().count("gem"));
        assertEquals(1, agent.profile().activities().progress("battle-win-1").value());
    }

    @Test
    void sweepBattleStageRunsInsidePlayerMailboxAfterClear() {
        RecordingExecutor executor = new RecordingExecutor();
        PlayerGameAgent agent = createBattleAgent(executor);
        AtomicReference<BattleSettlementResult> clear = new AtomicReference<>();
        AtomicReference<BattleSettlementResult> sweep = new AtomicReference<>();

        agent.clearBattleStage("settle-10001-1", "forest-1", clear::set);
        executor.runNext();
        agent.sweepBattleStage("sweep-10001-1", "forest-1", sweep::set);
        executor.runNext();

        assertEquals(3, clear.get().stars());
        assertTrue(sweep.get().swept());
        assertEquals(110, agent.profile().growth().stamina());
        assertEquals(2, sweep.get().clearCount());
        assertEquals(60, agent.profile().bag().count("gold"));
        assertEquals(5, agent.profile().bag().count("gem"));
        assertEquals(2, agent.profile().activities().progress("battle-win-1").value());
    }

    private static PlayerGameAgent createAgent(Executor executor) {
        return createAgent(executor, Clock.systemUTC(), Instant.EPOCH);
    }

    private static PlayerGameAgent createAgent(Executor executor, Clock clock, Instant serverOpenTime) {
        return createAgent(executor, clock, serverOpenTime, PlayerPushPort.NOOP);
    }

    private static PlayerGameAgent createAgent(
            Executor executor,
            Clock clock,
            Instant serverOpenTime,
            PlayerPushPort pushes
    ) {
        ActorSystem actors = new ActorSystem(executor, 64);
        return createAgent(actors, executor, clock, serverOpenTime, pushes);
    }

    private static PlayerGameAgent createAgent(
            ActorSystem actors,
            Executor executor,
            Clock clock,
            Instant serverOpenTime,
            PlayerPushPort pushes
    ) {
        ActorRef self = actors.actor("player-10001");
        ItemCatalog items = new ItemCatalog();
        items.register(new ItemDefinition("gold", "currency", 999999));
        items.register(new ItemDefinition("exp_potion", "growth", 999));
        items.register(new ItemDefinition("gem", "currency", 999999));
        BagService bagService = new BagService(items);
        ActivityCatalog activities = new ActivityCatalog();
        activities.register(new ActivityDefinition(
                "daily-login",
                ActivityType.LOGIN,
                1,
                Reward.of(new ItemStack("gold", 100), new ItemStack("exp_potion", 2))
        ));
        activities.register(new ActivityDefinition(
                "kill-3",
                ActivityType.COUNTER,
                3,
                Reward.of(new ItemStack("gem", 5))
        ));
        activities.register(new ActivityDefinition(
                "open-day-2",
                ActivityType.COUNTER,
                1,
                Reward.of(new ItemStack("gem", 2)),
                com.commonbattle.game.activity.ActivitySchedule.openServerWindow(Duration.ofDays(1), Duration.ofDays(3)),
                com.commonbattle.game.activity.ParticipationCondition.always()
        ));
        ActivityService activityService = new ActivityService(activities, bagService);
        GrowthService growthService = new GrowthService(bagService, "exp_potion", 60, 100);
        return new PlayerGameAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                self,
                new PlayerProfile(10001L),
                activityService,
                growthService,
                clock,
                serverOpenTime,
                pushes
        );
    }

    private static PlayerGameAgent createShopAgent(Executor executor) {
        ActorSystem actors = new ActorSystem(executor, 64);
        ActorRef self = actors.actor("player-10001");
        ItemCatalog items = new ItemCatalog();
        items.register(new ItemDefinition("gold", "currency", 999999));
        items.register(new ItemDefinition("exp_potion", "growth", 999));
        BagService bagService = new BagService(items);
        ActivityCatalog activities = new ActivityCatalog();
        activities.register(new ActivityDefinition(
                "daily-login",
                ActivityType.LOGIN,
                1,
                Reward.of(new ItemStack("gold", 100))
        ));
        ShopCatalog shops = new ShopCatalog();
        shops.register(new ShopItemDefinition(
                "growth_pack",
                new ItemStack("gold", 50),
                Reward.of(new ItemStack("exp_potion", 1)),
                2,
                1,
                ShopItemDefinition.UNLIMITED_STOCK
        ));
        ActivityService activityService = new ActivityService(activities, bagService);
        GrowthService growthService = new GrowthService(bagService, "exp_potion", 60, 100);
        ShopService shopService = new ShopService(
                shops,
                bagService,
                com.commonbattle.game.shop.ShopStockRepository.unlimited(),
                new InMemoryShopOrderRepository(),
                CLOCK,
                ZoneOffset.UTC
        );
        return new PlayerGameAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                self,
                new PlayerProfile(10001L),
                activityService,
                growthService,
                shopService,
                CLOCK,
                Instant.parse("2026-08-01T00:00:00Z")
        );
    }

    private static PlayerGameAgent createBattleAgent(Executor executor) {
        ActorSystem actors = new ActorSystem(executor, 64);
        ActorRef self = actors.actor("player-10001");
        ItemCatalog items = new ItemCatalog();
        items.register(new ItemDefinition("gold", "currency", 999999));
        items.register(new ItemDefinition("exp_potion", "growth", 999));
        items.register(new ItemDefinition("gem", "currency", 999999));
        BagService bagService = new BagService(items);
        ActivityCatalog activities = new ActivityCatalog();
        activities.register(new ActivityDefinition(
                "battle-win-1",
                ActivityType.COUNTER,
                1,
                Reward.of(new ItemStack("gem", 2))
        ));
        ActivityService activityService = new ActivityService(activities, bagService);
        GrowthService growthService = new GrowthService(bagService, "exp_potion", 60, 100);
        ShopService shopService = new ShopService(new ShopCatalog(), bagService, CLOCK, ZoneOffset.UTC);
        BattleStageCatalog battles = new BattleStageCatalog();
        battles.register(new BattleStageDefinition(
                "forest-1",
                100,
                40,
                70,
                8,
                5,
                Reward.of(new ItemStack("gold", 30)),
                "battle-win-1",
                1,
                Reward.of(new ItemStack("gem", 5)),
                3,
                5
        ));
        BattleService battleService = new BattleService(battles, bagService, activityService);
        return new PlayerGameAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                self,
                new PlayerProfile(10001L),
                activityService,
                growthService,
                shopService,
                battleService,
                CLOCK,
                Instant.parse("2026-08-01T00:00:00Z")
        );
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            commands.add(command);
        }

        synchronized int queued() {
            return commands.size();
        }

        synchronized void runNext() {
            commands.removeFirst().run();
        }
    }

    private static final class RecordingPushPort implements PlayerPushPort {
        private final List<PushedMessage> messages = new ArrayList<>();

        @Override
        public PlayerOutboundDeliveryResult push(Set<Long> recipients, String topic, Object payload) {
            messages.add(new PushedMessage(Set.copyOf(recipients), topic, payload));
            return PlayerOutboundDeliveryResult.empty();
        }
    }

    private record PushedMessage(Set<Long> recipients, String topic, Object payload) {
    }

    private static boolean awaitQueued(RecordingExecutor executor, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (executor.queued() >= expected) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return executor.queued() >= expected;
    }
}
