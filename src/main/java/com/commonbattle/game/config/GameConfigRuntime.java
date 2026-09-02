package com.commonbattle.game.config;

import com.commonbattle.game.activity.ActivityCatalog;
import com.commonbattle.game.activity.ActivityService;
import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemCatalog;
import com.commonbattle.game.growth.GrowthService;

/**
 * 一个配置版本构建出的运行时服务集合。
 * 玩家 Agent 创建时应持有同一个版本的 runtime，避免活动和物品表读取不同版本。
 */
public record GameConfigRuntime(
        long version,
        GameConfigPackage source,
        ItemCatalog items,
        BagService bagService,
        ActivityCatalog activities,
        ActivityService activityService,
        GrowthService growthService
) {
    public static GameConfigRuntime from(GameConfigPackage config) {
        ItemCatalog items = new ItemCatalog();
        config.items().forEach(items::register);
        BagService bagService = new BagService(items);
        ActivityCatalog activities = new ActivityCatalog();
        config.activities().forEach(activities::register);
        return new GameConfigRuntime(
                config.version(),
                config,
                items,
                bagService,
                activities,
                new ActivityService(activities, bagService),
                new GrowthService(
                        bagService,
                        config.growth().expItemId(),
                        config.growth().expPerItem(),
                        config.growth().expPerLevel()
                )
        );
    }
}
