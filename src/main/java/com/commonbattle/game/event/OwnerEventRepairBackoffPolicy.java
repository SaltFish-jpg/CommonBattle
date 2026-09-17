package com.commonbattle.game.event;

import java.time.Duration;
import java.util.Objects;

/**
 * owner 投影修复失败后的退避策略。
 * 线上通常按 topic 配置，避免远端快照或事件回放故障时反复立即重试。
 */
public record OwnerEventRepairBackoffPolicy(
        Duration initialDelay,
        Duration maxDelay,
        double multiplier
) {
    private static final OwnerEventRepairBackoffPolicy DISABLED =
            new OwnerEventRepairBackoffPolicy(Duration.ZERO, Duration.ZERO, 1.0);

    public OwnerEventRepairBackoffPolicy {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must not be negative");
        }
        if (maxDelay.isNegative()) {
            throw new IllegalArgumentException("maxDelay must not be negative");
        }
        if (!initialDelay.isZero() && maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be greater than or equal to initialDelay");
        }
        if (Double.isNaN(multiplier) || Double.isInfinite(multiplier) || multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be greater than or equal to 1.0");
        }
    }

    public static OwnerEventRepairBackoffPolicy disabled() {
        return DISABLED;
    }

    public boolean enabled() {
        return !initialDelay.isZero() && !maxDelay.isZero();
    }

    public Duration delayForFailure(int consecutiveFailures) {
        if (!enabled() || consecutiveFailures <= 0) {
            return Duration.ZERO;
        }
        double factor = Math.pow(multiplier, consecutiveFailures - 1);
        double delayNanos = initialDelay.toNanos() * factor;
        if (delayNanos >= maxDelay.toNanos()) {
            return maxDelay;
        }
        return Duration.ofNanos(Math.max(1L, (long) delayNanos));
    }
}
