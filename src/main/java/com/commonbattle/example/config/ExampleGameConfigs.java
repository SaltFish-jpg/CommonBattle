package com.commonbattle.example.config;

import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.activity.ActivitySchedule;
import com.commonbattle.game.activity.ActivityType;
import com.commonbattle.game.activity.ParticipationCondition;
import com.commonbattle.game.achievement.AchievementDefinition;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.battle.BattleStageDefinition;
import com.commonbattle.game.config.GameConfigPackage;
import com.commonbattle.game.config.GrowthTuning;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.EventProgressRule;
import com.commonbattle.game.shop.ShopItemDefinition;
import com.commonbattle.game.task.TaskDefinition;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 示例业务配置。
 * 独立部署样例用它完成启动预热，真实项目可替换为 DB 或配置仓库加载。
 */
public final class ExampleGameConfigs {
    private ExampleGameConfigs() {
    }

    public static GameConfigPackage basic(long version, Instant createdAt) {
        return new GameConfigPackage(
                version,
                List.of(
                        new ItemDefinition("gold", "currency", 999999),
                        new ItemDefinition("exp_potion", "growth", 999),
                        new ItemDefinition("gem", "currency", 999999)
                ),
                List.of(
                        new ActivityDefinition(
                                "daily-login",
                                ActivityType.LOGIN,
                                1,
                                Reward.of(new ItemStack("gold", 100), new ItemStack("exp_potion", 1))
                        ),
                        new ActivityDefinition(
                                "open-day-2",
                                ActivityType.COUNTER,
                                1,
                                Reward.of(new ItemStack("gem", 2)),
                                ActivitySchedule.openServerWindow(Duration.ofDays(1), Duration.ofDays(3)),
                                ParticipationCondition.minLevel(1)
                        ),
                        new ActivityDefinition(
                                "battle-win-1",
                                ActivityType.COUNTER,
                                1,
                                Reward.of(new ItemStack("gem", 2)),
                                ActivitySchedule.alwaysOpen(),
                                ParticipationCondition.always(),
                                EventProgressRule.of(BattleStageClearedEvent.TYPE, "forest-1")
                        )
                ),
                List.of(new ShopItemDefinition(
                        "growth_pack",
                        new ItemStack("gold", 50),
                        Reward.of(new ItemStack("exp_potion", 1)),
                        2,
                        1,
                        ShopItemDefinition.UNLIMITED_STOCK
                )),
                List.of(new BattleStageDefinition(
                        "forest-1",
                        100,
                        40,
                        70,
                        8,
                        5,
                        Reward.of(new ItemStack("gold", 30), new ItemStack("exp_potion", 1)),
                        "",
                        0,
                        Reward.of(new ItemStack("gem", 5)),
                        3
                )),
                List.of(new TaskDefinition(
                        "task-clear-forest",
                        EventProgressRule.of(BattleStageClearedEvent.TYPE, "forest-1"),
                        1,
                        Reward.of(new ItemStack("gem", 3))
                )),
                List.of(new AchievementDefinition(
                        "achievement-clear-forest",
                        EventProgressRule.of(BattleStageClearedEvent.TYPE, "forest-1"),
                        1,
                        Reward.of(new ItemStack("gem", 10))
                )),
                new GrowthTuning("exp_potion", 60, 100),
                createdAt
        );
    }
}
