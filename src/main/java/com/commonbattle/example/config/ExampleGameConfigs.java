package com.commonbattle.example.config;

import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.activity.ActivitySchedule;
import com.commonbattle.game.activity.ActivityType;
import com.commonbattle.game.activity.ParticipationCondition;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.config.GameConfigPackage;
import com.commonbattle.game.config.GrowthTuning;

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
                        )
                ),
                new GrowthTuning("exp_potion", 60, 100),
                createdAt
        );
    }
}
