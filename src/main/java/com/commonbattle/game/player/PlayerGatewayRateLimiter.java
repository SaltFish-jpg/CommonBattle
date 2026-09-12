package com.commonbattle.game.player;

import com.commonbattle.actor.backpressure.AgentRateLimitPolicy;

import java.time.Clock;
import java.util.Objects;

/**
 * 网关连接级令牌桶。
 */
final class PlayerGatewayRateLimiter {
    private final AgentRateLimitPolicy policy;
    private final Clock clock;
    private int permits;
    private long lastRefillMillis;

    PlayerGatewayRateLimiter(AgentRateLimitPolicy policy, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.permits = policy.capacity();
        this.lastRefillMillis = clock.millis();
    }

    synchronized boolean tryAcquire() {
        refill();
        if (permits <= 0) {
            return false;
        }
        permits--;
        return true;
    }

    private void refill() {
        long now = clock.millis();
        long elapsed = now - lastRefillMillis;
        long interval = policy.refillInterval().toMillis();
        if (elapsed < interval) {
            return;
        }
        long rounds = elapsed / interval;
        long refill = rounds * policy.refillPermits();
        permits = (int) Math.min(policy.capacity(), permits + refill);
        lastRefillMillis += rounds * interval;
    }
}
