package com.commonbattle.game.config;

import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.activity.ActivityType;
import com.commonbattle.game.achievement.AchievementDefinition;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.battle.BattleStageDefinition;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.EventProgressRule;
import com.commonbattle.game.shop.ShopItemDefinition;
import com.commonbattle.game.task.TaskDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameConfigValidatorTest {
    @Test
    void validConfigPassesCrossTableValidation() {
        GameConfigValidation validation = new GameConfigValidator().validate(validConfig(1));

        assertTrue(validation.valid());
    }

    @Test
    void collectsDuplicateIdsAndUnknownItemReferences() {
        GameConfigPackage config = new GameConfigPackage(
                1,
                List.of(
                        new ItemDefinition("gold", "currency", 999999),
                        new ItemDefinition("gold", "currency", 999999)
                ),
                List.of(
                        new ActivityDefinition(
                                "daily-login",
                                ActivityType.LOGIN,
                                1,
                                Reward.of(new ItemStack("missing_reward", 1))
                        ),
                        new ActivityDefinition(
                                "daily-login",
                                ActivityType.LOGIN,
                                1,
                                Reward.of(new ItemStack("gold", 1))
                        )
                ),
                new GrowthTuning("missing_exp", 60, 100),
                Instant.parse("2026-09-01T00:00:00Z")
        );

        GameConfigValidation validation = new GameConfigValidator().validate(config);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(GameConfigIssue::key).toList();
        assertTrue(keys.contains("items.gold"));
        assertTrue(keys.contains("activities.daily-login"));
        assertTrue(keys.contains("activities.daily-login.reward.missing_reward"));
        assertTrue(keys.contains("growth.expItemId"));
    }

    @Test
    void collectsShopDuplicateIdsAndUnknownItemReferences() {
        GameConfigPackage config = new GameConfigPackage(
                1,
                List.of(new ItemDefinition("gold", "currency", 999999)),
                List.of(),
                List.of(
                        new ShopItemDefinition(
                                "growth_pack",
                                new ItemStack("missing_price", 10),
                                Reward.of(new ItemStack("gold", 1)),
                                0,
                                0,
                                ShopItemDefinition.UNLIMITED_STOCK
                        ),
                        new ShopItemDefinition(
                                "growth_pack",
                                new ItemStack("gold", 10),
                                Reward.of(new ItemStack("missing_reward", 1)),
                                0,
                                0,
                                ShopItemDefinition.UNLIMITED_STOCK
                        )
                ),
                new GrowthTuning("gold", 60, 100),
                Instant.parse("2026-09-01T00:00:00Z")
        );

        GameConfigValidation validation = new GameConfigValidator().validate(config);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(GameConfigIssue::key).toList();
        assertTrue(keys.contains("shops.growth_pack"));
        assertTrue(keys.contains("shops.growth_pack.price.missing_price"));
        assertTrue(keys.contains("shops.growth_pack.reward.missing_reward"));
    }

    @Test
    void collectsBattleDuplicateIdsAndUnknownReferences() {
        GameConfigPackage config = new GameConfigPackage(
                1,
                List.of(new ItemDefinition("gold", "currency", 999999)),
                List.of(new ActivityDefinition(
                        "battle-win-1",
                        ActivityType.COUNTER,
                        1,
                        Reward.of(new ItemStack("gold", 1))
                )),
                List.of(),
                List.of(
                        new BattleStageDefinition(
                                "forest-1",
                                100,
                                40,
                                70,
                                8,
                                5,
                                Reward.of(new ItemStack("missing_reward", 1)),
                                "battle-win-1",
                                1
                        ),
                        new BattleStageDefinition(
                                "forest-1",
                                100,
                                40,
                                70,
                                8,
                                5,
                                Reward.of(new ItemStack("gold", 1)),
                                "missing_activity",
                                1,
                                Reward.of(new ItemStack("missing_first_clear", 1)),
                                0
                        )
                ),
                new GrowthTuning("gold", 60, 100),
                Instant.parse("2026-09-01T00:00:00Z")
        );

        GameConfigValidation validation = new GameConfigValidator().validate(config);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(GameConfigIssue::key).toList();
        assertTrue(keys.contains("battles.forest-1"));
        assertTrue(keys.contains("battles.forest-1.reward.missing_reward"));
        assertTrue(keys.contains("battles.forest-1.firstClearReward.missing_first_clear"));
        assertTrue(keys.contains("battles.forest-1.progressActivityId"));
    }

    @Test
    void collectsTaskDuplicateIdsAndUnknownRewardReferences() {
        GameConfigPackage config = new GameConfigPackage(
                1,
                List.of(new ItemDefinition("gem", "currency", 999999)),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new TaskDefinition(
                                "task-clear-forest",
                                BattleStageClearedEvent.TYPE,
                                "forest-1",
                                1,
                                Reward.of(new ItemStack("missing_reward", 1))
                        ),
                        new TaskDefinition(
                                "task-clear-forest",
                                BattleStageClearedEvent.TYPE,
                                "forest-1",
                                1,
                                Reward.of(new ItemStack("gem", 1))
                        )
                ),
                new GrowthTuning("gem", 60, 100),
                Instant.parse("2026-09-01T00:00:00Z")
        );

        GameConfigValidation validation = new GameConfigValidator().validate(config);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(GameConfigIssue::key).toList();
        assertTrue(keys.contains("tasks.task-clear-forest"));
        assertTrue(keys.contains("tasks.task-clear-forest.reward.missing_reward"));
    }

    @Test
    void collectsAchievementDuplicateIdsAndUnknownRewardReferences() {
        GameConfigPackage config = new GameConfigPackage(
                1,
                List.of(new ItemDefinition("gem", "currency", 999999)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new AchievementDefinition(
                                "achievement-clear-forest",
                                BattleStageClearedEvent.TYPE,
                                "forest-1",
                                1,
                                Reward.of(new ItemStack("missing_reward", 1))
                        ),
                        new AchievementDefinition(
                                "achievement-clear-forest",
                                BattleStageClearedEvent.TYPE,
                                "forest-1",
                                1,
                                Reward.of(new ItemStack("gem", 1))
                        )
                ),
                new GrowthTuning("gem", 60, 100),
                Instant.parse("2026-09-01T00:00:00Z")
        );

        GameConfigValidation validation = new GameConfigValidator().validate(config);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(GameConfigIssue::key).toList();
        assertTrue(keys.contains("achievements.achievement-clear-forest"));
        assertTrue(keys.contains("achievements.achievement-clear-forest.reward.missing_reward"));
    }


    @Test
    void rejectsEventProgressRuleOnNonCounterActivity() {
        GameConfigPackage config = new GameConfigPackage(
                1,
                List.of(new ItemDefinition("gold", "currency", 999999)),
                List.of(new ActivityDefinition(
                        "daily-login",
                        ActivityType.LOGIN,
                        1,
                        Reward.of(new ItemStack("gold", 1)),
                        com.commonbattle.game.activity.ActivitySchedule.alwaysOpen(),
                        com.commonbattle.game.activity.ParticipationCondition.always(),
                        EventProgressRule.of(BattleStageClearedEvent.TYPE, "forest-1")
                )),
                new GrowthTuning("gold", 60, 100),
                Instant.parse("2026-09-01T00:00:00Z")
        );

        GameConfigValidation validation = new GameConfigValidator().validate(config);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(GameConfigIssue::key).toList();
        assertTrue(keys.contains("activities.daily-login.progressRule"));
    }

    static GameConfigPackage validConfig(long version) {
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
                new GrowthTuning("exp_potion", 60, 100),
                Instant.parse("2026-09-01T00:00:00Z")
        );
    }
}
