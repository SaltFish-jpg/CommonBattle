package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.activity.ActivityType;
import com.commonbattle.game.achievement.AchievementClaimResult;
import com.commonbattle.game.bag.InMemoryBagChangeAuditLog;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.battle.BattleSettlementResult;
import com.commonbattle.game.config.GameConfigPackage;
import com.commonbattle.game.config.GameConfigRegistry;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.GrowthTuning;
import com.commonbattle.game.config.InMemoryGameConfigRegistry;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.game.event.ReliableVersionedEventPublisher;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.event.VersionedEventRetryPolicy;
import com.commonbattle.game.player.event.BagChangedEvent;
import com.commonbattle.game.player.event.BagChangeAuditEventHandler;
import com.commonbattle.game.player.event.EventProgressRule;
import com.commonbattle.game.player.event.PlayerDomainEventProcessor;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.player.event.ShopItemPurchasedEvent;
import com.commonbattle.game.session.PlayerOutboundDeliveryResult;
import com.commonbattle.game.session.PlayerOutboundTopicPolicies;
import com.commonbattle.game.shop.ShopItemDefinition;
import com.commonbattle.game.shop.ShopPurchaseResult;
import com.commonbattle.game.shop.ShopPurchaseStatus;
import com.commonbattle.game.task.TaskClaimResult;
import com.commonbattle.game.task.TaskDefinition;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerGameAgentConfigRuntimeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void businessMessageBindsConfigRuntimeOnceEvenWhenConfigPublishesInsideHandler() {
        RecordingExecutor executor = new RecordingExecutor();
        GameConfigRegistry registry = registry();
        registry.publish(config(1, 60));
        PlayerGameAgent agent = createAgent(executor, registry);
        AtomicLong boundVersion = new AtomicLong();
        AtomicReference<Integer> expAfterFirstMessage = new AtomicReference<>();

        agent.execute(execution -> {
            boundVersion.set(execution.runtime().version());
            execution.runtime().requireBagService().grant(
                    execution.profile().bag(),
                    Reward.of(new ItemStack("exp_potion", 1))
            );
            registry.publish(config(2, 120));
            execution.runtime().growthService().useExpItems(
                    execution.profile().bag(),
                    execution.profile().growth(),
                    1
            );
            expAfterFirstMessage.set(execution.profile().growth().exp());
        });
        executor.runNext();

        assertEquals(1, boundVersion.get());
        assertEquals(60, expAfterFirstMessage.get());
        agent.execute(execution -> {
            execution.runtime().requireBagService().grant(
                    execution.profile().bag(),
                    Reward.of(new ItemStack("exp_potion", 1))
            );
            execution.runtime().growthService().useExpItems(
                    execution.profile().bag(),
                    execution.profile().growth(),
                    1
            );
        });
        executor.runNext();
        assertEquals(80, agent.profile().growth().exp());
        assertEquals(2, agent.profile().growth().level());
    }

    @Test
    void grayRuntimeIsSelectedByPlayerWhenCommandRuns() {
        RecordingExecutor executor = new RecordingExecutor();
        GameConfigRegistry registry = registry();
        registry.publish(config(1, 60));
        registry.publishGray(config(2, 120), 100);
        PlayerGameAgent agent = createAgent(executor, registry);
        AtomicLong version = new AtomicLong();

        agent.execute(execution -> version.set(execution.runtime().version()));
        executor.runNext();

        assertEquals(2, version.get());
    }

    @Test
    void rollbackChangesRuntimeSeenByLaterMessages() {
        RecordingExecutor executor = new RecordingExecutor();
        GameConfigRegistry registry = registry();
        registry.publish(config(1, 60));
        registry.publish(config(2, 120));
        PlayerGameAgent agent = createAgent(executor, registry);
        AtomicLong first = new AtomicLong();
        AtomicLong second = new AtomicLong();

        agent.execute(execution -> first.set(execution.runtime().version()));
        executor.runNext();
        registry.rollback(1);
        agent.execute(execution -> second.set(execution.runtime().version()));
        executor.runNext();

        assertEquals(2, first.get());
        assertEquals(1, second.get());
    }

    @Test
    void consumeBagItemPublishesBagChangedEventWithAttributes() {
        RecordingExecutor executor = new RecordingExecutor();
        GameConfigRegistry registry = registry();
        registry.publish(config(1, 60));
        List<VersionedEvent> published = new ArrayList<>();
        PlayerGameAgent agent = createAgent(executor, registry, published::add, 0, 0);
        agent.profile().bag().restore(new BagSnapshot(Map.of("exp_potion", 3)));

        agent.consumeBagItem("exp_potion", 2, ignored -> {
        });
        executor.runNext();

        PlayerDomainVersionedEvent event = (PlayerDomainVersionedEvent) published.getFirst();
        assertEquals(BagChangedEvent.TYPE, event.eventType());
        assertEquals(PlayerBusinessOperations.BAG_CONSUME_ITEM, event.subject());
        assertEquals(2, event.delta());
        assertEquals("exp_potion", event.attributes().get("change.0.itemId"));
        assertEquals("3", event.attributes().get("change.0.before"));
        assertEquals("1", event.attributes().get("change.0.after"));
    }

    @Test
    void consumeBagItemEventCanBeWrittenToBagChangeAudit() {
        RecordingExecutor executor = new RecordingExecutor();
        GameConfigRegistry registry = registry();
        registry.publish(config(1, 60));
        List<VersionedEvent> published = new ArrayList<>();
        PlayerGameAgent agent = createAgent(executor, registry, published::add, 0, 0);
        agent.profile().bag().restore(new BagSnapshot(Map.of("exp_potion", 3)));
        InMemoryBagChangeAuditLog audits = new InMemoryBagChangeAuditLog();
        PlayerDomainEventProcessor processor = new PlayerDomainEventProcessor();
        processor.on(BagChangedEvent.TYPE, new BagChangeAuditEventHandler(audits));

        agent.consumeBagItem("exp_potion", 2, ignored -> {
        });
        executor.runNext();
        processor.apply((PlayerDomainVersionedEvent) published.getFirst());

        assertEquals(1, audits.stats().recorded());
        assertEquals("exp_potion", audits.records().getFirst().itemId());
        assertEquals(3, audits.records().getFirst().before());
        assertEquals(1, audits.records().getFirst().after());
    }

    @Test
    void battleEventProgressesActivityTaskAndAchievementInsideConfigRuntime() {
        RecordingExecutor executor = new RecordingExecutor();
        GameConfigRegistry registry = registry();
        registry.publish(ExampleGameConfigs.basic(1, CLOCK.instant()));
        PlayerGameAgent agent = createAgent(executor, registry);
        AtomicReference<BattleSettlementResult> battle = new AtomicReference<>();
        AtomicReference<TaskClaimResult> task = new AtomicReference<>();
        AtomicReference<AchievementClaimResult> achievement = new AtomicReference<>();

        agent.clearBattleStage("settle-10001-1", "forest-1", battle::set);
        executor.runNext();
        agent.claimTask("task-clear-forest", task::set);
        executor.runNext();
        agent.claimAchievement("achievement-clear-forest", achievement::set);
        executor.runNext();

        assertEquals(1, agent.profile().activities().progress("battle-win-1").value());
        assertEquals(1, agent.profile().tasks().progress("task-clear-forest").value());
        assertEquals(1, agent.profile().achievements().progress("achievement-clear-forest").value());
        assertEquals(18, agent.profile().bag().count("gem"));
        assertEquals(1, task.get().progress());
        assertEquals(1, achievement.get().progress());
        assertEquals(3, battle.get().stars());
    }

    @Test
    void battleEventPushesDerivedBusinessSnapshotsInsideSameMailboxMessage() {
        RecordingExecutor executor = new RecordingExecutor();
        GameConfigRegistry registry = registry();
        registry.publish(ExampleGameConfigs.basic(1, CLOCK.instant()));
        RecordingPushPort pushes = new RecordingPushPort();
        PlayerGameAgent agent = createAgent(executor, registry, pushes);

        agent.clearBattleStage("settle-10001-1", "forest-1", ignored -> {
        });
        executor.runNext();

        assertEquals(List.of(
                PlayerOutboundTopicPolicies.GROWTH_SNAPSHOT,
                PlayerOutboundTopicPolicies.ACTIVITY_PROGRESS,
                PlayerOutboundTopicPolicies.TASK_PROGRESS,
                PlayerOutboundTopicPolicies.ACHIEVEMENT_PROGRESS,
                PlayerOutboundTopicPolicies.BATTLE_SNAPSHOT,
                PlayerOutboundTopicPolicies.BAG_SNAPSHOT
        ), pushes.messages.stream().map(PushedMessage::topic).toList());
    }

    @Test
    void shopEventPushesTaskAndAchievementProgressBeforePurchaseSnapshots() {
        RecordingExecutor executor = new RecordingExecutor();
        GameConfigRegistry registry = registry();
        registry.publish(shopProgressConfig(1));
        RecordingPushPort pushes = new RecordingPushPort();
        PlayerGameAgent agent = createAgent(executor, registry, pushes);
        AtomicReference<ShopPurchaseResult> purchase = new AtomicReference<>();

        agent.onLogin("daily-login", ignored -> {
        });
        executor.runNext();
        pushes.messages.clear();

        agent.buyShopItem("order-10001-growth-pack", "growth_pack", 1, purchase::set);
        executor.runNext();

        assertEquals(ShopPurchaseStatus.SUCCESS, purchase.get().status());
        assertEquals(1, agent.profile().tasks().progress("task-buy-growth-pack").value());
        assertEquals(1, agent.profile().achievements().progress("achievement-buy-growth-pack").value());
        assertEquals(List.of(
                PlayerOutboundTopicPolicies.TASK_PROGRESS,
                PlayerOutboundTopicPolicies.ACHIEVEMENT_PROGRESS,
                PlayerOutboundTopicPolicies.SHOP_SNAPSHOT,
                PlayerOutboundTopicPolicies.BAG_SNAPSHOT
        ), pushes.messages.stream().map(PushedMessage::topic).toList());
    }

    @Test
    void playerDomainEventPublishFailureKeepsOutboxPendingWithoutBlockingLocalSettlement() {
        RecordingExecutor executor = new RecordingExecutor();
        GameConfigRegistry registry = registry();
        registry.publish(ExampleGameConfigs.basic(1, CLOCK.instant()));
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(CLOCK);
        List<VersionedEvent> published = new ArrayList<>();
        ReliableVersionedEventPublisher publisher = new ReliableVersionedEventPublisher(
                outbox,
                new FailingOncePublisher(published),
                new VersionedEventRetryPolicy(
                        3,
                        Duration.ZERO,
                        Duration.ZERO,
                        10
                ),
                CLOCK
        );
        PlayerGameAgent agent = createAgent(executor, registry, publisher, 0, 0);

        agent.clearBattleStage("settle-10001-1", "forest-1", ignored -> {
        });
        executor.runNext();

        assertEquals(1, agent.profile().activities().progress("battle-win-1").value());
        assertEquals(1, agent.profile().tasks().progress("task-clear-forest").value());
        assertEquals(1, agent.profile().achievements().progress("achievement-clear-forest").value());
        assertEquals(1, outbox.pending().size());
        assertEquals(1, outbox.pending().getFirst().attempts());

        publisher.replayPending();

        assertTrue(outbox.pending().isEmpty());
        PlayerDomainVersionedEvent event = published.stream()
                .filter(PlayerDomainVersionedEvent.class::isInstance)
                .map(PlayerDomainVersionedEvent.class::cast)
                .filter(candidate -> "battle.stage.cleared".equals(candidate.eventType()))
                .findFirst()
                .orElseThrow();
        assertEquals(10001L, event.playerId());
        assertEquals("battle.stage.cleared", event.eventType());
        assertEquals("forest-1", event.subject());
        assertEquals(1, event.revision());
    }

    private static PlayerGameAgent createAgent(Executor executor, GameConfigRegistry registry) {
        return createAgent(executor, registry, null, 0, 0);
    }

    private static PlayerGameAgent createAgent(
            Executor executor,
            GameConfigRegistry registry,
            PlayerPushPort pushes
    ) {
        ActorSystem actors = new ActorSystem(executor, 64);
        ActorRef self = actors.actor("player-10001");
        return new PlayerGameAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                self,
                new PlayerProfile(10001L),
                registry,
                CLOCK,
                Instant.parse("2026-08-01T00:00:00Z"),
                null,
                0,
                0,
                null,
                null,
                new AsyncShopPurchaseMetrics(),
                pushes
        );
    }

    private static PlayerGameAgent createAgent(
            Executor executor,
            GameConfigRegistry registry,
            EventPublisher domainEventPublisher,
            long initialStateRevision,
            long initialEventRevision
    ) {
        ActorSystem actors = new ActorSystem(executor, 64);
        ActorRef self = actors.actor("player-10001");
        return new PlayerGameAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                self,
                new PlayerProfile(10001L),
                registry,
                CLOCK,
                Instant.parse("2026-08-01T00:00:00Z"),
                domainEventPublisher,
                initialStateRevision,
                initialEventRevision
        );
    }

    private static InMemoryGameConfigRegistry registry() {
        return new InMemoryGameConfigRegistry(new GameConfigValidator(), CLOCK);
    }

    private static GameConfigPackage config(long version, int expPerItem) {
        return new GameConfigPackage(
                version,
                List.of(
                        new ItemDefinition("gold", "currency", 999999),
                        new ItemDefinition("exp_potion", "growth", 999)
                ),
                List.of(new ActivityDefinition(
                        "daily-login",
                        ActivityType.LOGIN,
                        1,
                        Reward.of(new ItemStack("gold", 100), new ItemStack("exp_potion", 1))
                )),
                new GrowthTuning("exp_potion", expPerItem, 100),
                CLOCK.instant()
        );
    }

    private static GameConfigPackage shopProgressConfig(long version) {
        return new GameConfigPackage(
                version,
                List.of(
                        new ItemDefinition("gold", "currency", 999999),
                        new ItemDefinition("exp_potion", "growth", 999),
                        new ItemDefinition("gem", "currency", 999999)
                ),
                List.of(new ActivityDefinition(
                        "daily-login",
                        ActivityType.LOGIN,
                        1,
                        Reward.of(new ItemStack("gold", 100))
                )),
                List.of(new ShopItemDefinition(
                        "growth_pack",
                        new ItemStack("gold", 50),
                        Reward.of(new ItemStack("exp_potion", 1)),
                        2,
                        1,
                        ShopItemDefinition.UNLIMITED_STOCK
                )),
                List.of(),
                List.of(new TaskDefinition(
                        "task-buy-growth-pack",
                        EventProgressRule.of(ShopItemPurchasedEvent.TYPE, "growth_pack"),
                        1,
                        Reward.of(new ItemStack("gem", 1))
                )),
                List.of(new com.commonbattle.game.achievement.AchievementDefinition(
                        "achievement-buy-growth-pack",
                        EventProgressRule.of(ShopItemPurchasedEvent.TYPE, "growth_pack"),
                        1,
                        Reward.of(new ItemStack("gem", 2))
                )),
                new GrowthTuning("exp_potion", 60, 100),
                CLOCK.instant()
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

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }

    private static final class FailingOncePublisher implements EventPublisher {
        private final List<VersionedEvent> published;
        private boolean fail = true;

        private FailingOncePublisher(List<VersionedEvent> published) {
            this.published = published;
        }

        @Override
        public void publish(VersionedEvent event) {
            if (fail) {
                fail = false;
                throw new IllegalStateException("temporary network failure");
            }
            published.add(event);
        }
    }
}
