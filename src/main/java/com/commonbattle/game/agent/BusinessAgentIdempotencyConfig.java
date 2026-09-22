package com.commonbattle.game.agent;

import java.time.Duration;
import java.util.Objects;

/**
 * 业务 Agent RPC 端点幂等缓存配置。
 * capacity 为 0 时关闭缓存；ttl 为 0 时完成态结果不过期，只受容量淘汰约束。
 */
public record BusinessAgentIdempotencyConfig(int capacity, Duration ttl) {
    public static final int DEFAULT_CAPACITY = 4096;
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    public BusinessAgentIdempotencyConfig {
        Objects.requireNonNull(ttl, "ttl");
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative");
        }
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must not be negative");
        }
    }

    public static BusinessAgentIdempotencyConfig defaults() {
        return new BusinessAgentIdempotencyConfig(DEFAULT_CAPACITY, DEFAULT_TTL);
    }
}
