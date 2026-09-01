package com.commonbattle.actor.backpressure;

import java.time.Duration;
import java.util.Objects;

/**
 * Agent 入站限流策略。
 * capacity 是瞬时突发容量，refillPermits/refillInterval 表示令牌补充速度。
 */
public record AgentRateLimitPolicy(int capacity, int refillPermits, Duration refillInterval) {
    public AgentRateLimitPolicy {
        Objects.requireNonNull(refillInterval, "refillInterval");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (refillPermits <= 0) {
            throw new IllegalArgumentException("refillPermits must be positive");
        }
        if (refillInterval.isZero() || refillInterval.isNegative()) {
            throw new IllegalArgumentException("refillInterval must be positive");
        }
    }

    public static AgentRateLimitPolicy perSecond(int capacity, int refillPermits) {
        return new AgentRateLimitPolicy(capacity, refillPermits, Duration.ofSeconds(1));
    }
}
