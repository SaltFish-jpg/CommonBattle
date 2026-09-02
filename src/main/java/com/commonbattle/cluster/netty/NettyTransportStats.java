package com.commonbattle.cluster.netty;

/**
 * Netty 跨服传输运行统计。
 * 用于区分 RPC 业务失败和底层连接、写入、收包问题。
 */
public record NettyTransportStats(
        int activeConnections,
        long connectionAttempts,
        long connectionFailures,
        long sentEnvelopes,
        long failedWrites,
        long receivedEnvelopes,
        long inboundFailures
) {
    public NettyTransportStats {
        if (activeConnections < 0 || connectionAttempts < 0 || connectionFailures < 0
                || sentEnvelopes < 0 || failedWrites < 0 || receivedEnvelopes < 0 || inboundFailures < 0) {
            throw new IllegalArgumentException("netty transport stats must not be negative");
        }
    }
}
