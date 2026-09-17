package com.commonbattle.game.growth;

import java.time.Instant;
import java.util.Objects;

/**
 * 玩家养成状态可持久化快照。
 */
public record GrowthSnapshot(
        int level,
        int exp,
        int stamina,
        int maxStamina,
        Instant staminaUpdatedAt
) {
    public static final int DEFAULT_MAX_STAMINA = 120;

    public GrowthSnapshot(int level, int exp) {
        this(level, exp, DEFAULT_MAX_STAMINA, DEFAULT_MAX_STAMINA, Instant.EPOCH);
    }

    public GrowthSnapshot {
        Objects.requireNonNull(staminaUpdatedAt, "staminaUpdatedAt");
        if (level <= 0) {
            throw new IllegalArgumentException("level must be positive");
        }
        if (exp < 0) {
            throw new IllegalArgumentException("exp must not be negative");
        }
        if (maxStamina <= 0) {
            throw new IllegalArgumentException("maxStamina must be positive");
        }
        if (stamina < 0 || stamina > maxStamina) {
            throw new IllegalArgumentException("stamina must be between 0 and maxStamina");
        }
    }
}
