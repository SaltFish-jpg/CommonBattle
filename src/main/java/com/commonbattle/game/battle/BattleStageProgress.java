package com.commonbattle.game.battle;

import java.time.Instant;
import java.util.Objects;

/**
 * 玩家在单个 PVE 关卡上的长期进度。
 * 记录首通时间、通关次数和最佳星级，供首通奖励、扫荡和后续关卡解锁使用。
 */
public final class BattleStageProgress {
    private final String stageId;
    private int clearCount;
    private int bestStars;
    private Instant firstClearedAt = Instant.EPOCH;
    private Instant updatedAt = Instant.EPOCH;

    public BattleStageProgress(String stageId) {
        this.stageId = requireStageId(stageId);
    }

    public String stageId() {
        return stageId;
    }

    public int clearCount() {
        return clearCount;
    }

    public int bestStars() {
        return bestStars;
    }

    public Instant firstClearedAt() {
        return firstClearedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean cleared() {
        return clearCount > 0;
    }

    public boolean recordVictory(int stars, Instant now) {
        Objects.requireNonNull(now, "now");
        if (stars <= 0 || stars > 3) {
            throw new IllegalArgumentException("stars must be 1..3");
        }
        boolean firstClear = clearCount == 0;
        clearCount++;
        bestStars = Math.max(bestStars, stars);
        if (firstClear) {
            firstClearedAt = now;
        }
        updatedAt = now;
        return firstClear;
    }

    public BattleStageProgressSnapshot snapshot() {
        return new BattleStageProgressSnapshot(stageId, clearCount, bestStars, firstClearedAt, updatedAt);
    }

    public void restore(BattleStageProgressSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!stageId.equals(snapshot.stageId())) {
            throw new IllegalArgumentException("stage progress snapshot stageId mismatch");
        }
        clearCount = snapshot.clearCount();
        bestStars = snapshot.bestStars();
        firstClearedAt = snapshot.firstClearedAt();
        updatedAt = snapshot.updatedAt();
    }

    private static String requireStageId(String stageId) {
        Objects.requireNonNull(stageId, "stageId");
        if (stageId.isBlank()) {
            throw new IllegalArgumentException("stageId must not be blank");
        }
        return stageId;
    }
}
