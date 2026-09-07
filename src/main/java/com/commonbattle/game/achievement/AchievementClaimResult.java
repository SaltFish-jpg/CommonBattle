package com.commonbattle.game.achievement;

import com.commonbattle.game.bag.BagResult;

import java.util.Objects;

/**
 * 玩家成就领奖结果。
 */
public record AchievementClaimResult(String achievementId, int progress, BagResult reward) {
    public AchievementClaimResult {
        Objects.requireNonNull(achievementId, "achievementId");
        Objects.requireNonNull(reward, "reward");
    }
}
