package com.commonbattle.game.battle;

import com.commonbattle.game.bag.BagResult;

import java.util.Objects;

/**
 * 已完成战斗结算请求的持久化快照。
 * 用于玩家 Agent 重启、迁服或 RPC 重试后回放同一个 settlementId 的结果。
 */
public record BattleSettlementSnapshot(
        String settlementId,
        String stageId,
        BattleSettlementStatus status,
        int rounds,
        int playerHp,
        int enemyHp,
        BagResult rewardResult,
        String progressActivityId,
        int progressDelta,
        int stars,
        boolean firstClear,
        int clearCount,
        boolean swept
) {
    public BattleSettlementSnapshot(
            String settlementId,
            String stageId,
            BattleSettlementStatus status,
            int rounds,
            int playerHp,
            int enemyHp,
            BagResult rewardResult,
            String progressActivityId,
            int progressDelta
    ) {
        this(settlementId, stageId, status, rounds, playerHp, enemyHp, rewardResult,
                progressActivityId, progressDelta, 0, false, 0, false);
    }

    public BattleSettlementSnapshot {
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(stageId, "stageId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(rewardResult, "rewardResult");
        progressActivityId = Objects.requireNonNullElse(progressActivityId, "");
        if (settlementId.isBlank()) {
            throw new IllegalArgumentException("settlementId must not be blank");
        }
        if (stageId.isBlank()) {
            throw new IllegalArgumentException("stageId must not be blank");
        }
    }

    public BattleSettlementResult toResult() {
        return new BattleSettlementResult(
                settlementId,
                stageId,
                status,
                rounds,
                playerHp,
                enemyHp,
                rewardResult,
                progressActivityId,
                progressDelta,
                stars,
                firstClear,
                clearCount,
                java.util.List.of(),
                swept,
                false
        );
    }
}
