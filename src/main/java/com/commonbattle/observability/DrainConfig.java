package com.commonbattle.observability;

import java.time.Duration;
import java.util.Objects;

/**
 * 停服排水配置。
 */
public record DrainConfig(Duration timeout, Duration pollInterval, Duration propagationDelay) {
    public DrainConfig(Duration timeout, Duration pollInterval) {
        this(timeout, pollInterval, Duration.ZERO);
    }

    public DrainConfig {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(pollInterval, "pollInterval");
        Objects.requireNonNull(propagationDelay, "propagationDelay");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        if (propagationDelay.isNegative()) {
            throw new IllegalArgumentException("propagationDelay must not be negative");
        }
    }

    public static DrainConfig defaults() {
        return new DrainConfig(Duration.ofSeconds(10), Duration.ofMillis(50), Duration.ZERO);
    }
}
