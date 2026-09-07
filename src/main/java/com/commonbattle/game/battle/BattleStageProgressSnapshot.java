package com.commonbattle.game.battle;

import java.time.Instant;
import java.util.Objects;

/**
 * 玩家单个关卡进度快照。
 */
public record BattleStageProgressSnapshot(
        String stageId,
        int clearCount,
        int bestStars,
        Instant firstClearedAt,
        Instant updatedAt
) {
    public BattleStageProgressSnapshot {
        Objects.requireNonNull(stageId, "stageId");
        Objects.requireNonNull(firstClearedAt, "firstClearedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (stageId.isBlank()) {
            throw new IllegalArgumentException("stageId must not be blank");
        }
        if (clearCount < 0) {
            throw new IllegalArgumentException("clearCount must not be negative");
        }
        if (bestStars < 0 || bestStars > 3) {
            throw new IllegalArgumentException("bestStars must be 0..3");
        }
    }
}
