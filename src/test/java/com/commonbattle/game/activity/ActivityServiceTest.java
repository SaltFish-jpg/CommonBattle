package com.commonbattle.game.activity;

import com.commonbattle.game.GameBusinessErrorCodes;
import com.commonbattle.game.GameBusinessFailure;
import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemCatalog;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.PlayerBag;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.EventProgressRule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActivityServiceTest {
    @Test
    void counterActivityCanBeClaimedOnceAfterThreshold() {
        ItemCatalog items = new ItemCatalog();
        items.register(new ItemDefinition("gem", "currency", 999999));
        ActivityCatalog activities = new ActivityCatalog();
        activities.register(new ActivityDefinition(
                "kill-3",
                ActivityType.COUNTER,
                3,
                Reward.of(new ItemStack("gem", 10))
        ));
        ActivityService service = new ActivityService(activities, new BagService(items));
        PlayerActivities playerActivities = new PlayerActivities();
        PlayerBag bag = new PlayerBag();

        service.increase(playerActivities, "kill-3", 2);
        IllegalStateException notReady = assertThrows(
                IllegalStateException.class,
                () -> service.claim(playerActivities, bag, "kill-3")
        );
        assertEquals(
                GameBusinessErrorCodes.ACTIVITY_REWARD_NOT_READY,
                assertInstanceOf(GameBusinessFailure.class, notReady).code()
        );

        service.increase(playerActivities, "kill-3", 1);
        service.claim(playerActivities, bag, "kill-3");

        assertEquals(10, bag.count("gem"));
        IllegalStateException alreadyClaimed = assertThrows(
                IllegalStateException.class,
                () -> service.claim(playerActivities, bag, "kill-3")
        );
        assertEquals(
                GameBusinessErrorCodes.ACTIVITY_REWARD_ALREADY_CLAIMED,
                assertInstanceOf(GameBusinessFailure.class, alreadyClaimed).code()
        );
    }

    @Test
    void naturalTimeActivityOnlyWorksInsideWindow() {
        ItemCatalog items = new ItemCatalog();
        items.register(new ItemDefinition("gem", "currency", 999999));
        ActivityCatalog activities = new ActivityCatalog();
        activities.register(new ActivityDefinition(
                "spring-login",
                ActivityType.LOGIN,
                1,
                Reward.of(new ItemStack("gem", 1)),
                ActivitySchedule.naturalWindow(Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z")),
                ParticipationCondition.always()
        ));
        ActivityService service = new ActivityService(activities, new BagService(items));
        PlayerActivities playerActivities = new PlayerActivities();
        ActivityAccessContext beforeOpen = new ActivityAccessContext(
                Instant.parse("2026-01-31T23:59:59Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                ActivityParticipant.none()
        );
        ActivityAccessContext opened = new ActivityAccessContext(
                Instant.parse("2026-02-10T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                ActivityParticipant.none()
        );

        IllegalStateException notOpen = assertThrows(IllegalStateException.class, () ->
                service.recordLogin(playerActivities, beforeOpen, "spring-login"));
        assertEquals(
                GameBusinessErrorCodes.ACTIVITY_NOT_OPEN,
                assertInstanceOf(GameBusinessFailure.class, notOpen).code()
        );

        service.recordLogin(playerActivities, opened, "spring-login");

        assertEquals(1, playerActivities.progress("spring-login").value());
    }

    @Test
    void openServerActivityUsesServerOpenTimeAndMinLevelCondition() {
        ItemCatalog items = new ItemCatalog();
        items.register(new ItemDefinition("gem", "currency", 999999));
        ActivityCatalog activities = new ActivityCatalog();
        activities.register(new ActivityDefinition(
                "open-day-2",
                ActivityType.COUNTER,
                1,
                Reward.of(new ItemStack("gem", 1)),
                ActivitySchedule.openServerWindow(Duration.ofDays(1), Duration.ofDays(3)),
                ParticipationCondition.minLevel(10)
        ));
        ActivityService service = new ActivityService(activities, new BagService(items));
        Instant serverOpenTime = Instant.parse("2026-08-01T00:00:00Z");
        ActivityAccessContext tooEarly = new ActivityAccessContext(
                Instant.parse("2026-08-01T12:00:00Z"),
                serverOpenTime,
                participant(20)
        );
        ActivityAccessContext lowLevel = new ActivityAccessContext(
                Instant.parse("2026-08-02T12:00:00Z"),
                serverOpenTime,
                participant(9)
        );
        ActivityAccessContext eligible = new ActivityAccessContext(
                Instant.parse("2026-08-02T12:00:00Z"),
                serverOpenTime,
                participant(10)
        );
        PlayerActivities playerActivities = new PlayerActivities();

        IllegalStateException tooEarlyFailure = assertThrows(IllegalStateException.class, () ->
                service.increase(playerActivities, tooEarly, "open-day-2", 1));
        assertEquals(
                GameBusinessErrorCodes.ACTIVITY_NOT_OPEN,
                assertInstanceOf(GameBusinessFailure.class, tooEarlyFailure).code()
        );
        IllegalStateException lowLevelFailure = assertThrows(IllegalStateException.class, () ->
                service.increase(playerActivities, lowLevel, "open-day-2", 1));
        assertEquals(
                GameBusinessErrorCodes.ACTIVITY_NOT_ELIGIBLE,
                assertInstanceOf(GameBusinessFailure.class, lowLevelFailure).code()
        );

        service.increase(playerActivities, eligible, "open-day-2", 1);

        assertEquals(1, playerActivities.progress("open-day-2").value());
    }

    @Test
    void counterActivityCanProgressFromPlayerDomainEventRule() {
        ItemCatalog items = new ItemCatalog();
        items.register(new ItemDefinition("gem", "currency", 999999));
        ActivityCatalog activities = new ActivityCatalog();
        activities.register(new ActivityDefinition(
                "battle-win-1",
                ActivityType.COUNTER,
                1,
                Reward.of(new ItemStack("gem", 1)),
                ActivitySchedule.alwaysOpen(),
                ParticipationCondition.always(),
                EventProgressRule.of(BattleStageClearedEvent.TYPE, "forest-1")
        ));
        ActivityService service = new ActivityService(activities, new BagService(items));
        PlayerActivities playerActivities = new PlayerActivities();

        int matched = service.onEvent(playerActivities, ActivityAccessContext.alwaysAllowed(), new BattleStageClearedEvent(
                10001L,
                "forest-1",
                3,
                true,
                1,
                false,
                false,
                "",
                1
        ));

        assertEquals(1, matched);
        assertEquals(1, playerActivities.progress("battle-win-1").value());
    }

    private static ActivityParticipant participant(int level) {
        return new ActivityParticipant() {
            @Override
            public long playerId() {
                return 10001L;
            }

            @Override
            public int level() {
                return level;
            }

            @Override
            public Instant createdAt() {
                return Instant.EPOCH;
            }
        };
    }
}
