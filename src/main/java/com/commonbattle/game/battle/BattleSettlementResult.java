package com.commonbattle.game.battle;

import com.commonbattle.core.BattleLogEntry;
import com.commonbattle.game.bag.BagResult;

import java.util.List;
import java.util.Objects;

/**
 * 玩家战斗结算结果。
 * 胜利时 rewardResult 记录实际入包变更，失败或平局时为空。
 */
public record BattleSettlementResult(
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
        List<BattleLogEntry> log,
        boolean swept,
        boolean replayed
) {
    public BattleSettlementResult(
            String stageId,
            BattleSettlementStatus status,
            int rounds,
            int playerHp,
            int enemyHp,
            BagResult rewardResult,
            String progressActivityId,
            int progressDelta,
            List<BattleLogEntry> log
    ) {
        this("", stageId, status, rounds, playerHp, enemyHp, rewardResult, progressActivityId, progressDelta,
                0, false, 0, log, false, false);
    }

    public BattleSettlementResult(
            String settlementId,
            String stageId,
            BattleSettlementStatus status,
            int rounds,
            int playerHp,
            int enemyHp,
            BagResult rewardResult,
            String progressActivityId,
            int progressDelta,
            List<BattleLogEntry> log,
            boolean replayed
    ) {
        this(settlementId, stageId, status, rounds, playerHp, enemyHp, rewardResult, progressActivityId, progressDelta,
                0, false, 0, log, false, replayed);
    }

    public BattleSettlementResult {
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(stageId, "stageId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(rewardResult, "rewardResult");
        progressActivityId = Objects.requireNonNullElse(progressActivityId, "");
        log = List.copyOf(log);
        if (stageId.isBlank()) {
            throw new IllegalArgumentException("stageId must not be blank");
        }
        if (rounds < 0) {
            throw new IllegalArgumentException("rounds must not be negative");
        }
        if (progressDelta < 0) {
            throw new IllegalArgumentException("progressDelta must not be negative");
        }
        if (stars < 0 || stars > 3) {
            throw new IllegalArgumentException("stars must be 0..3");
        }
        if (clearCount < 0) {
            throw new IllegalArgumentException("clearCount must not be negative");
        }
    }

    public boolean victory() {
        return status == BattleSettlementStatus.VICTORY;
    }

    public BattleSettlementResult asReplayed() {
        return new BattleSettlementResult(settlementId, stageId, status, rounds, playerHp, enemyHp,
                rewardResult, progressActivityId, progressDelta, stars, firstClear, clearCount, log, swept, true);
    }
}
