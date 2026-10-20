package com.commonbattle.cluster.network;

import java.time.Duration;
import java.util.Objects;

/**
 * 代理服逐跳可靠投递配置。
 * ACK 超时确认目标网关是否真正收包，pending 上限用于隔离下游故障时的内存压力。
 */
public record ForwardingProxyConfig(Duration deliveryAckTimeout, int maxPendingForwards) {
    public ForwardingProxyConfig {
        Objects.requireNonNull(deliveryAckTimeout, "deliveryAckTimeout");
        if (deliveryAckTimeout.isZero() || deliveryAckTimeout.isNegative()) {
            throw new IllegalArgumentException("deliveryAckTimeout must be positive");
        }
        if (maxPendingForwards <= 0) {
            throw new IllegalArgumentException("maxPendingForwards must be positive");
        }
    }

    public static ForwardingProxyConfig defaults() {
        return new ForwardingProxyConfig(Duration.ofSeconds(1), 10_000);
    }
}
