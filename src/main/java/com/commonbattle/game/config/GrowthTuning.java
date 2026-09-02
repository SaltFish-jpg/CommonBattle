package com.commonbattle.game.config;

import java.util.Objects;

/**
 * 养成模块配置参数。
 */
public record GrowthTuning(String expItemId, int expPerItem, int expPerLevel) {
    public GrowthTuning {
        Objects.requireNonNull(expItemId, "expItemId");
        if (expItemId.isBlank()) {
            throw new IllegalArgumentException("expItemId must not be blank");
        }
        if (expPerItem <= 0) {
            throw new IllegalArgumentException("expPerItem must be positive");
        }
        if (expPerLevel <= 0) {
            throw new IllegalArgumentException("expPerLevel must be positive");
        }
    }
}
