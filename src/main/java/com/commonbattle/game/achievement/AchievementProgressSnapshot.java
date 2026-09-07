package com.commonbattle.game.achievement;

/**
 * 玩家成就进度快照。
 */
public record AchievementProgressSnapshot(int value, boolean claimed) {
    public AchievementProgressSnapshot {
        if (value < 0) {
            throw new IllegalArgumentException("value must not be negative");
        }
    }
}
