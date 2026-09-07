package com.commonbattle.game.config;

import com.commonbattle.game.activity.ActivityCatalog;
import com.commonbattle.game.activity.ActivityService;
import com.commonbattle.game.achievement.AchievementCatalog;
import com.commonbattle.game.achievement.AchievementService;
import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemCatalog;
import com.commonbattle.game.battle.BattleService;
import com.commonbattle.game.battle.BattleStageCatalog;
import com.commonbattle.game.growth.GrowthService;
import com.commonbattle.game.shop.InMemoryShopOrderRepository;
import com.commonbattle.game.shop.ShopCatalog;
import com.commonbattle.game.shop.ShopService;
import com.commonbattle.game.task.TaskCatalog;
import com.commonbattle.game.task.TaskService;

import java.time.Clock;
import java.time.ZoneOffset;

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
        ShopCatalog shops,
        ShopService shopService,
        BattleStageCatalog battles,
        BattleService battleService,
        TaskCatalog tasks,
        TaskService taskService,
        AchievementCatalog achievements,
        AchievementService achievementService,
        GrowthService growthService
) {
    public static GameConfigRuntime from(GameConfigPackage config) {
        ItemCatalog items = new ItemCatalog();
        config.items().forEach(items::register);
        BagService bagService = new BagService(items);
        ActivityCatalog activities = new ActivityCatalog();
        config.activities().forEach(activities::register);
        ActivityService activityService = new ActivityService(activities, bagService);
        ShopCatalog shops = new ShopCatalog();
        config.shops().forEach(shops::register);
        BattleStageCatalog battles = new BattleStageCatalog();
        config.battles().forEach(battles::register);
        BattleService battleService = new BattleService(battles, bagService);
        TaskCatalog tasks = new TaskCatalog();
        config.tasks().forEach(tasks::register);
        TaskService taskService = new TaskService(tasks, bagService);
        AchievementCatalog achievements = new AchievementCatalog();
        config.achievements().forEach(achievements::register);
        AchievementService achievementService = new AchievementService(achievements, bagService);
        return new GameConfigRuntime(
                config.version(),
                config,
                items,
                bagService,
                activities,
                activityService,
                shops,
                new ShopService(shops, bagService, com.commonbattle.game.shop.ShopStockRepository.unlimited(),
                        new InMemoryShopOrderRepository(), Clock.systemUTC(), ZoneOffset.UTC),
                battles,
                battleService,
                tasks,
                taskService,
                achievements,
                achievementService,
                new GrowthService(
                        bagService,
                        config.growth().expItemId(),
                        config.growth().expPerItem(),
                        config.growth().expPerLevel()
                )
        );
    }
}
