package com.commonbattle.actor.backpressure;

import java.time.Duration;
import java.util.Objects;

/**
 * 基于 Actor 邮箱积压的入站准入策略。
 * 目标邮箱阈值用于保护单个玩家或业务实体，业务组阈值用于保护同类 Actor 的整体调度压力。
 */
public record ActorMailboxPressurePolicy(
        boolean enabled,
        int maxTargetQueuedTasks,
        int maxGroupQueuedTasks,
        Duration retryAfter
) {
    public ActorMailboxPressurePolicy {
        Objects.requireNonNull(retryAfter, "retryAfter");
        if (maxTargetQueuedTasks < 0 || maxGroupQueuedTasks < 0) {
            throw new IllegalArgumentException("mailbox pressure limits must not be negative");
        }
        if (retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
    }

    public static ActorMailboxPressurePolicy disabled() {
        return new ActorMailboxPressurePolicy(false, 0, 0, Duration.ZERO);
    }

    public boolean hasTargetLimit() {
        return maxTargetQueuedTasks > 0;
    }

    public boolean hasGroupLimit() {
        return maxGroupQueuedTasks > 0;
    }

    public boolean active() {
        return enabled && (hasTargetLimit() || hasGroupLimit());
    }
}
