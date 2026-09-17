package com.commonbattle.game.config;

import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.achievement.AchievementDefinition;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.battle.BattleStageDefinition;
import com.commonbattle.game.shop.ShopItemDefinition;
import com.commonbattle.game.task.TaskDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 游戏业务配置校验器。
 * 发布前统一校验跨表引用，避免活动奖励或养成消耗引用不存在的物品。
 */
public final class GameConfigValidator {
    public GameConfigValidation validate(GameConfigPackage config) {
        List<GameConfigIssue> issues = new ArrayList<>();
        Set<String> itemIds = validateItems(config, issues);
        Set<String> activityIds = validateActivities(config, itemIds, issues);
        validateShops(config, itemIds, issues);
        validateBattles(config, itemIds, activityIds, issues);
        validateTasks(config, itemIds, issues);
        validateAchievements(config, itemIds, issues);
        validateGrowth(config, itemIds, issues);
        return new GameConfigValidation(issues);
    }

    private Set<String> validateItems(GameConfigPackage config, List<GameConfigIssue> issues) {
        Set<String> ids = new HashSet<>();
        for (ItemDefinition item : config.items()) {
            if (!ids.add(item.itemId())) {
                issues.add(new GameConfigIssue("items." + item.itemId(), "duplicate item id"));
            }
        }
        return ids;
    }

    private Set<String> validateActivities(GameConfigPackage config, Set<String> itemIds, List<GameConfigIssue> issues) {
        Set<String> ids = new HashSet<>();
        for (ActivityDefinition activity : config.activities()) {
            if (!ids.add(activity.activityId())) {
                issues.add(new GameConfigIssue("activities." + activity.activityId(), "duplicate activity id"));
            }
            if (activity.progressRule().enabled() && activity.type() != com.commonbattle.game.activity.ActivityType.COUNTER) {
                issues.add(new GameConfigIssue("activities." + activity.activityId() + ".progressRule",
                        "event progress rule requires counter activity"));
            }
            for (ItemStack item : activity.reward().items()) {
                if (!itemIds.contains(item.itemId())) {
                    issues.add(new GameConfigIssue(
                            "activities." + activity.activityId() + ".reward." + item.itemId(),
                            "unknown reward item"
                    ));
                }
            }
        }
        return ids;
    }

    private void validateGrowth(GameConfigPackage config, Set<String> itemIds, List<GameConfigIssue> issues) {
        if (!itemIds.contains(config.growth().expItemId())) {
            issues.add(new GameConfigIssue("growth.expItemId", "unknown exp item"));
        }
    }

    private void validateShops(GameConfigPackage config, Set<String> itemIds, List<GameConfigIssue> issues) {
        Set<String> ids = new HashSet<>();
        for (ShopItemDefinition shop : config.shops()) {
            if (!ids.add(shop.sku())) {
                issues.add(new GameConfigIssue("shops." + shop.sku(), "duplicate shop item id"));
            }
            if (!itemIds.contains(shop.price().itemId())) {
                issues.add(new GameConfigIssue("shops." + shop.sku() + ".price." + shop.price().itemId(),
                        "unknown price item"));
            }
            for (ItemStack item : shop.reward().items()) {
                if (!itemIds.contains(item.itemId())) {
                    issues.add(new GameConfigIssue("shops." + shop.sku() + ".reward." + item.itemId(),
                            "unknown reward item"));
                }
            }
        }
    }

    private void validateBattles(
            GameConfigPackage config,
            Set<String> itemIds,
            Set<String> activityIds,
            List<GameConfigIssue> issues
    ) {
        Set<String> ids = new HashSet<>();
        for (BattleStageDefinition battle : config.battles()) {
            if (!ids.add(battle.stageId())) {
                issues.add(new GameConfigIssue("battles." + battle.stageId(), "duplicate battle stage id"));
            }
            for (ItemStack item : battle.victoryReward().items()) {
                if (!itemIds.contains(item.itemId())) {
                    issues.add(new GameConfigIssue("battles." + battle.stageId() + ".reward." + item.itemId(),
                            "unknown reward item"));
                }
            }
            for (ItemStack item : battle.firstClearReward().items()) {
                if (!itemIds.contains(item.itemId())) {
                    issues.add(new GameConfigIssue("battles." + battle.stageId() + ".firstClearReward." + item.itemId(),
                            "unknown first clear reward item"));
                }
            }
            if (!battle.progressActivityId().isBlank() && !activityIds.contains(battle.progressActivityId())) {
                issues.add(new GameConfigIssue("battles." + battle.stageId() + ".progressActivityId",
                        "unknown progress activity"));
            }
            if (battle.staminaCost() < 0) {
                issues.add(new GameConfigIssue("battles." + battle.stageId() + ".staminaCost",
                        "must not be negative"));
            }
        }
    }

    private void validateTasks(GameConfigPackage config, Set<String> itemIds, List<GameConfigIssue> issues) {
        Set<String> ids = new HashSet<>();
        for (TaskDefinition task : config.tasks()) {
            if (!ids.add(task.taskId())) {
                issues.add(new GameConfigIssue("tasks." + task.taskId(), "duplicate task id"));
            }
            for (ItemStack item : task.reward().items()) {
                if (!itemIds.contains(item.itemId())) {
                    issues.add(new GameConfigIssue("tasks." + task.taskId() + ".reward." + item.itemId(),
                            "unknown reward item"));
                }
            }
        }
    }

    private void validateAchievements(GameConfigPackage config, Set<String> itemIds, List<GameConfigIssue> issues) {
        Set<String> ids = new HashSet<>();
        for (AchievementDefinition achievement : config.achievements()) {
            if (!ids.add(achievement.achievementId())) {
                issues.add(new GameConfigIssue("achievements." + achievement.achievementId(),
                        "duplicate achievement id"));
            }
            for (ItemStack item : achievement.reward().items()) {
                if (!itemIds.contains(item.itemId())) {
                    issues.add(new GameConfigIssue("achievements." + achievement.achievementId()
                            + ".reward." + item.itemId(), "unknown reward item"));
                }
            }
        }
    }
}
