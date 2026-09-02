package com.commonbattle.game.config;

import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;

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
        validateActivities(config, itemIds, issues);
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

    private void validateActivities(GameConfigPackage config, Set<String> itemIds, List<GameConfigIssue> issues) {
        Set<String> ids = new HashSet<>();
        for (ActivityDefinition activity : config.activities()) {
            if (!ids.add(activity.activityId())) {
                issues.add(new GameConfigIssue("activities." + activity.activityId(), "duplicate activity id"));
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
    }

    private void validateGrowth(GameConfigPackage config, Set<String> itemIds, List<GameConfigIssue> issues) {
        if (!itemIds.contains(config.growth().expItemId())) {
            issues.add(new GameConfigIssue("growth.expItemId", "unknown exp item"));
        }
    }
}
