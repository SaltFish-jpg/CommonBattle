package com.commonbattle.game.battle;

import com.commonbattle.game.bag.Reward;

import java.util.Objects;

/**
 * 玩家 PVE 战斗关卡配置。
 * 该配置只描述业务结算需要的最小战斗参数，复杂战斗可替换为外部战斗服务或更完整的战斗模板。
 */
public record BattleStageDefinition(
        String stageId,
        int playerHp,
        int playerAttack,
        int enemyHp,
        int enemyAttack,
        int maxRounds,
        Reward victoryReward,
        String progressActivityId,
        int progressDelta,
        Reward firstClearReward,
        int sweepRequiredStars
) {
    public BattleStageDefinition(
            String stageId,
            int playerHp,
            int playerAttack,
            int enemyHp,
            int enemyAttack,
            int maxRounds,
            Reward victoryReward,
            String progressActivityId,
            int progressDelta
    ) {
        this(stageId, playerHp, playerAttack, enemyHp, enemyAttack, maxRounds, victoryReward,
                progressActivityId, progressDelta, Reward.of(), 0);
    }

    public BattleStageDefinition {
        Objects.requireNonNull(stageId, "stageId");
        Objects.requireNonNull(victoryReward, "victoryReward");
        Objects.requireNonNull(firstClearReward, "firstClearReward");
        progressActivityId = Objects.requireNonNullElse(progressActivityId, "");
        if (stageId.isBlank()) {
            throw new IllegalArgumentException("stageId must not be blank");
        }
        if (playerHp <= 0 || enemyHp <= 0) {
            throw new IllegalArgumentException("hp must be positive");
        }
        if (playerAttack <= 0 || enemyAttack <= 0) {
            throw new IllegalArgumentException("attack must be positive");
        }
        if (maxRounds <= 0) {
            throw new IllegalArgumentException("maxRounds must be positive");
        }
        if (progressDelta < 0) {
            throw new IllegalArgumentException("progressDelta must not be negative");
        }
        if (progressActivityId.isBlank() && progressDelta > 0) {
            throw new IllegalArgumentException("progressActivityId is required when progressDelta is positive");
        }
        if (sweepRequiredStars < 0 || sweepRequiredStars > 3) {
            throw new IllegalArgumentException("sweepRequiredStars must be 0..3");
        }
    }

    public boolean sweepable() {
        return sweepRequiredStars > 0;
    }
}
