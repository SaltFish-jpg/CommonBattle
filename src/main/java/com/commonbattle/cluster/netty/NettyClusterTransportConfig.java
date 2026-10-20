package com.commonbattle.cluster.netty;

import java.time.Duration;
import java.util.Objects;

/**
 * Netty 跨服连接治理配置。
 * 待发送上限约束单个故障目标占用的内存，重连退避用于抑制不可达节点上的连接风暴。
 */
public record NettyClusterTransportConfig(
        Duration connectTimeout,
        Duration reconnectBackoff,
        int maxPendingWritesPerTarget
) {
    public NettyClusterTransportConfig {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(reconnectBackoff, "reconnectBackoff");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (connectTimeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("connectTimeout exceeds Netty integer millisecond limit");
        }
        if (reconnectBackoff.isNegative()) {
            throw new IllegalArgumentException("reconnectBackoff must not be negative");
        }
        if (maxPendingWritesPerTarget <= 0) {
            throw new IllegalArgumentException("maxPendingWritesPerTarget must be positive");
        }
    }

    public static NettyClusterTransportConfig defaults() {
        return new NettyClusterTransportConfig(Duration.ofSeconds(2), Duration.ofMillis(200), 1_024);
    }
}
