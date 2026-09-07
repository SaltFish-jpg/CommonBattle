package com.commonbattle.game.achievement;

import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemCatalog;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.PlayerBag;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AchievementServiceTest {
    @Test
    void matchedPlayerEventIncreasesAchievementProgressAndClaimGrantsReward() {
        Fixture fixture = Fixture.create();
        PlayerAchievements achievements = new PlayerAchievements();
        PlayerBag bag = new PlayerBag();

        fixture.service().onEvent(achievements, new BattleStageClearedEvent(
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
        AchievementClaimResult claim = fixture.service().claim(achievements, bag, "achievement-clear-forest");

        assertEquals(1, claim.progress());
        assertEquals(10, bag.count("gem"));
    }

    @Test
    void replayedEventDoesNotIncreaseProgress() {
        Fixture fixture = Fixture.create();
        PlayerAchievements achievements = new PlayerAchievements();

        fixture.service().onEvent(achievements, new BattleStageClearedEvent(
                10001L,
                "forest-1",
                3,
                true,
                1,
                false,
                true,
                "",
                1
        ));

        assertEquals(0, achievements.progress("achievement-clear-forest").value());
    }

    @Test
    void claimBeforeReadyIsRejected() {
        Fixture fixture = Fixture.create();

        assertThrows(IllegalStateException.class,
                () -> fixture.service().claim(new PlayerAchievements(), new PlayerBag(), "achievement-clear-forest"));
    }

    private record Fixture(AchievementService service) {
        private static Fixture create() {
            ItemCatalog items = new ItemCatalog();
            items.register(new ItemDefinition("gem", "currency", 999999));
            BagService bagService = new BagService(items);
            AchievementCatalog achievements = new AchievementCatalog();
            achievements.register(new AchievementDefinition(
                    "achievement-clear-forest",
                    BattleStageClearedEvent.TYPE,
                    "forest-1",
                    1,
                    Reward.of(new ItemStack("gem", 10))
            ));
            return new Fixture(new AchievementService(achievements, bagService));
        }
    }
}
