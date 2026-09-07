package com.commonbattle.game.config;

import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.achievement.AchievementDefinition;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.battle.BattleStageDefinition;
import com.commonbattle.game.shop.ShopItemDefinition;
import com.commonbattle.game.task.TaskDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 游戏业务配置发布包。
 * 一个版本包内同时包含物品、活动、商店、战斗、任务、成就和养成配置，避免跨模块配置版本不一致。
 */
public record GameConfigPackage(
        long version,
        List<ItemDefinition> items,
        List<ActivityDefinition> activities,
        List<ShopItemDefinition> shops,
        List<BattleStageDefinition> battles,
        List<TaskDefinition> tasks,
        List<AchievementDefinition> achievements,
        GrowthTuning growth,
        Instant createdAt
) {
    public GameConfigPackage(
            long version,
            List<ItemDefinition> items,
            List<ActivityDefinition> activities,
            GrowthTuning growth,
            Instant createdAt
    ) {
        this(version, items, activities, List.of(), List.of(), List.of(), List.of(), growth, createdAt);
    }

    public GameConfigPackage(
            long version,
            List<ItemDefinition> items,
            List<ActivityDefinition> activities,
            List<ShopItemDefinition> shops,
            GrowthTuning growth,
            Instant createdAt
    ) {
        this(version, items, activities, shops, List.of(), List.of(), List.of(), growth, createdAt);
    }

    public GameConfigPackage(
            long version,
            List<ItemDefinition> items,
            List<ActivityDefinition> activities,
            List<ShopItemDefinition> shops,
            List<BattleStageDefinition> battles,
            GrowthTuning growth,
            Instant createdAt
    ) {
        this(version, items, activities, shops, battles, List.of(), List.of(), growth, createdAt);
    }

    public GameConfigPackage(
            long version,
            List<ItemDefinition> items,
            List<ActivityDefinition> activities,
            List<ShopItemDefinition> shops,
            List<BattleStageDefinition> battles,
            List<TaskDefinition> tasks,
            GrowthTuning growth,
            Instant createdAt
    ) {
        this(version, items, activities, shops, battles, tasks, List.of(), growth, createdAt);
    }

    public GameConfigPackage {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(activities, "activities");
        Objects.requireNonNull(shops, "shops");
        Objects.requireNonNull(battles, "battles");
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(achievements, "achievements");
        Objects.requireNonNull(growth, "growth");
        Objects.requireNonNull(createdAt, "createdAt");
        items = List.copyOf(items);
        activities = List.copyOf(activities);
        shops = List.copyOf(shops);
        battles = List.copyOf(battles);
        tasks = List.copyOf(tasks);
        achievements = List.copyOf(achievements);
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
    }
}
