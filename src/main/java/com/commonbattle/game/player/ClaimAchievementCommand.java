package com.commonbattle.game.player;

import com.commonbattle.game.achievement.AchievementClaimResult;

import java.util.Objects;

/**
 * 玩家成就领奖命令。
 */
public record ClaimAchievementCommand(String achievementId) implements PlayerBusinessCommand<AchievementClaimResult> {
    public ClaimAchievementCommand {
        Objects.requireNonNull(achievementId, "achievementId");
        if (achievementId.isBlank()) {
            throw new IllegalArgumentException("achievementId must not be blank");
        }
    }

    @Override
    public String operation() {
        return PlayerBusinessOperations.ACHIEVEMENT_CLAIM;
    }

    @Override
    public AchievementClaimResult execute(PlayerGameExecution execution) {
        return execution.runtime().requireAchievementService().claim(
                execution.profile().achievements(),
                execution.profile().bag(),
                achievementId
        );
    }
}
