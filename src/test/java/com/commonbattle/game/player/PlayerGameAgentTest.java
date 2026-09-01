package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
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
import com.commonbattle.game.growth.GrowthResult;
import com.commonbattle.game.growth.GrowthService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerGameAgentTest {
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

    private static PlayerGameAgent createAgent(Executor executor) {
        return createAgent(executor, Clock.systemUTC(), Instant.EPOCH);
    }

    private static PlayerGameAgent createAgent(Executor executor, Clock clock, Instant serverOpenTime) {
        ActorSystem actors = new ActorSystem(executor, 64);
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
                serverOpenTime
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
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.removeFirst().run();
        }
    }
}
