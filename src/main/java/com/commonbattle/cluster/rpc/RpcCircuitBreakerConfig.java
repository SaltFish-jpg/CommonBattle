package com.commonbattle.cluster.rpc;

import java.time.Duration;
import java.util.Objects;

/**
 * RPC 熔断器配置。
 * 连续失败达到阈值后进入 OPEN，在 openDuration 后允许新请求试探恢复。
 */
public record RpcCircuitBreakerConfig(int failureThreshold, Duration openDuration) {
    public RpcCircuitBreakerConfig {
        Objects.requireNonNull(openDuration, "openDuration");
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
        if (openDuration.isZero() || openDuration.isNegative()) {
            throw new IllegalArgumentException("openDuration must be positive");
        }
    }

    public static RpcCircuitBreakerConfig defaults() {
        return new RpcCircuitBreakerConfig(5, Duration.ofSeconds(5));
    }
}
