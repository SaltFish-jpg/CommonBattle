package com.commonbattle.game.achievement;

import java.util.Objects;

/**
 * 玩家单个成就进度。
 */
public final class AchievementProgress {
    private final String achievementId;
    private int value;
    private boolean claimed;

    public AchievementProgress(String achievementId) {
        this.achievementId = requireAchievementId(achievementId);
    }

    public int value() {
        return value;
    }

    public boolean claimed() {
        return claimed;
    }

    public void increase(int delta) {
        if (delta <= 0) {
            throw new IllegalArgumentException("delta must be positive");
        }
        value += delta;
    }

    public void claim() {
        if (claimed) {
            throw new IllegalStateException("Achievement reward already claimed: " + achievementId);
        }
        claimed = true;
    }

    public AchievementProgressSnapshot snapshot() {
        return new AchievementProgressSnapshot(value, claimed);
    }

    public void restore(AchievementProgressSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        value = snapshot.value();
        claimed = snapshot.claimed();
    }

    private static String requireAchievementId(String achievementId) {
        Objects.requireNonNull(achievementId, "achievementId");
        if (achievementId.isBlank()) {
            throw new IllegalArgumentException("achievementId must not be blank");
        }
        return achievementId;
    }
}
