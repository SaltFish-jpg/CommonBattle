package com.commonbattle.cluster.rpc;

import java.time.Duration;
import java.util.Objects;

/**
 * 跨服 RPC 治理配置。
 * pending 上限保护 callback 表，timeout 释放悬挂调用，slowCallThreshold 用于慢调用统计。
 */
public record RpcGovernanceConfig(
        Duration defaultTimeout,
        int maxPendingRequests,
        Duration slowCallThreshold,
        int idempotencyCacheCapacity
) {
    public RpcGovernanceConfig {
        Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        Objects.requireNonNull(slowCallThreshold, "slowCallThreshold");
        if (defaultTimeout.isZero() || defaultTimeout.isNegative()) {
            throw new IllegalArgumentException("defaultTimeout must be positive");
        }
        if (maxPendingRequests <= 0) {
            throw new IllegalArgumentException("maxPendingRequests must be positive");
        }
        if (slowCallThreshold.isZero() || slowCallThreshold.isNegative()) {
            throw new IllegalArgumentException("slowCallThreshold must be positive");
        }
        if (idempotencyCacheCapacity < 0) {
            throw new IllegalArgumentException("idempotencyCacheCapacity must not be negative");
        }
    }

    public static RpcGovernanceConfig defaults() {
        return new RpcGovernanceConfig(Duration.ofSeconds(3), 10_000, Duration.ofMillis(200), 10_000);
    }
}
