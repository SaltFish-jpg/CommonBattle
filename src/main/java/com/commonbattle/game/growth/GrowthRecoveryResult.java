package com.commonbattle.game.growth;

import java.time.Instant;
import java.util.Objects;

/**
 * 养成自然恢复结算结果。
 */
public record GrowthRecoveryResult(
        int beforeStamina,
        int afterStamina,
        int maxStamina,
        Instant beforeUpdatedAt,
        Instant afterUpdatedAt
) {
    public GrowthRecoveryResult {
        Objects.requireNonNull(beforeUpdatedAt, "beforeUpdatedAt");
        Objects.requireNonNull(afterUpdatedAt, "afterUpdatedAt");
        if (beforeStamina < 0 || afterStamina < 0) {
            throw new IllegalArgumentException("stamina must not be negative");
        }
        if (maxStamina <= 0) {
            throw new IllegalArgumentException("maxStamina must be positive");
        }
        if (afterStamina > maxStamina) {
            throw new IllegalArgumentException("afterStamina must not exceed maxStamina");
        }
    }

    public boolean changed() {
        return beforeStamina != afterStamina;
    }
}
