package com.commonbattle.observability;

import java.time.Duration;
import java.util.Objects;

/**
 * 停服排水配置。
 */
public record DrainConfig(Duration timeout, Duration pollInterval) {
    public DrainConfig {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
    }

    public static DrainConfig defaults() {
        return new DrainConfig(Duration.ofSeconds(10), Duration.ofMillis(50));
    }
}
