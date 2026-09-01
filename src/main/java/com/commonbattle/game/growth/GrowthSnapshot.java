package com.commonbattle.game.growth;

/**
 * 玩家养成状态可持久化快照。
 */
public record GrowthSnapshot(int level, int exp) {
    public GrowthSnapshot {
        if (level <= 0) {
            throw new IllegalArgumentException("level must be positive");
        }
        if (exp < 0) {
            throw new IllegalArgumentException("exp must not be negative");
        }
    }
}
