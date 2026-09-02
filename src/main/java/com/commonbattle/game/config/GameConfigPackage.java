package com.commonbattle.game.config;

import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.bag.ItemDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 游戏业务配置发布包。
 * 一个版本包内同时包含物品、活动和养成配置，避免跨模块配置版本不一致。
 */
public record GameConfigPackage(
        long version,
        List<ItemDefinition> items,
        List<ActivityDefinition> activities,
        GrowthTuning growth,
        Instant createdAt
) {
    public GameConfigPackage {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(activities, "activities");
        Objects.requireNonNull(growth, "growth");
        Objects.requireNonNull(createdAt, "createdAt");
        items = List.copyOf(items);
        activities = List.copyOf(activities);
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
    }
}
