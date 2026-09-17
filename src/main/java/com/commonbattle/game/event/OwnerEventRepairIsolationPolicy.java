package com.commonbattle.game.event;

import java.time.Duration;
import java.util.Objects;

/**
 * owner 投影修复的隔离策略。
 * 单个 owner 连续修复失败达到阈值后会进入隔离队列，到期后再回到普通修复队列。
 */
public record OwnerEventRepairIsolationPolicy(
        int maxFailures,
        Duration duration
) {
    private static final OwnerEventRepairIsolationPolicy DISABLED =
            new OwnerEventRepairIsolationPolicy(0, Duration.ZERO);

    public OwnerEventRepairIsolationPolicy {
        Objects.requireNonNull(duration, "duration");
        if (maxFailures < 0) {
            throw new IllegalArgumentException("maxFailures must not be negative");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
    }

    public static OwnerEventRepairIsolationPolicy disabled() {
        return DISABLED;
    }

    public boolean enabled() {
        return maxFailures > 0 && !duration.isZero();
    }
}
