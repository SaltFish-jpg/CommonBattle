package com.commonbattle.actor.agent;

import java.time.Duration;
import java.util.Objects;

/**
 * Agent owner 租约策略。
 */
public record AgentLeaseConfig(boolean enabled, Duration leaseTtl, Duration renewInterval) {
    public AgentLeaseConfig {
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        Objects.requireNonNull(renewInterval, "renewInterval");
        if (leaseTtl.isZero() || leaseTtl.isNegative()) {
            throw new IllegalArgumentException("leaseTtl must be positive");
        }
        if (renewInterval.isZero() || renewInterval.isNegative()) {
            throw new IllegalArgumentException("renewInterval must be positive");
        }
        if (renewInterval.compareTo(leaseTtl) >= 0) {
            throw new IllegalArgumentException("renewInterval must be shorter than leaseTtl");
        }
    }

    public static AgentLeaseConfig disabled() {
        return new AgentLeaseConfig(false, Duration.ofSeconds(15), Duration.ofSeconds(5));
    }

    public static AgentLeaseConfig defaults() {
        return new AgentLeaseConfig(true, Duration.ofSeconds(15), Duration.ofSeconds(5));
    }
}
