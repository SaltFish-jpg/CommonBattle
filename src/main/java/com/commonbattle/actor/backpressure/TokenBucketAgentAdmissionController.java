package com.commonbattle.actor.backpressure;

import com.commonbattle.actor.agent.AgentIdentity;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于令牌桶的 Agent 入站准入控制器。
 * 限流粒度是 AgentIdentity + operation，避免单个玩家或单类热点操作拖垮共享工作线程。
 */
public final class TokenBucketAgentAdmissionController implements InboundAdmissionController {
    private final AgentRateLimitPolicy policy;
    private final Clock clock;
    private final Map<Key, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketAgentAdmissionController(AgentRateLimitPolicy policy, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AdmissionDecision admit(AgentIdentity target, String operation) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operation, "operation");
        if (operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        Bucket bucket = buckets.computeIfAbsent(new Key(target, operation),
                ignored -> new Bucket(policy.capacity(), clock.millis()));
        synchronized (bucket) {
            refill(bucket);
            if (bucket.tokens > 0) {
                bucket.tokens--;
                return AdmissionDecision.accept();
            }
            return AdmissionDecision.reject("rate_limited", retryAfter(bucket));
        }
    }

    private void refill(Bucket bucket) {
        long now = clock.millis();
        long intervalMillis = policy.refillInterval().toMillis();
        long intervals = (now - bucket.lastRefillMillis) / intervalMillis;
        if (intervals <= 0) {
            return;
        }
        long added = intervals * policy.refillPermits();
        bucket.tokens = (int) Math.min(policy.capacity(), bucket.tokens + added);
        bucket.lastRefillMillis += intervals * intervalMillis;
    }

    private Duration retryAfter(Bucket bucket) {
        long intervalMillis = policy.refillInterval().toMillis();
        long elapsed = Math.max(0, clock.millis() - bucket.lastRefillMillis);
        return Duration.ofMillis(Math.max(1, intervalMillis - elapsed));
    }

    private record Key(AgentIdentity target, String operation) {
    }

    private static final class Bucket {
        private int tokens;
        private long lastRefillMillis;

        private Bucket(int tokens, long lastRefillMillis) {
            this.tokens = tokens;
            this.lastRefillMillis = lastRefillMillis;
        }
    }
}
