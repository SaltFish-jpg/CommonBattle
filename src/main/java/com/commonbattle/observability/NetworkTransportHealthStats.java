package com.commonbattle.observability;

/**
 * 跨服网络传输层健康统计。
 * 用于观察连接、写入和入站处理问题，和 RPC 业务成功失败分开统计。
 */
public record NetworkTransportHealthStats(
        int transportCount,
        int activeConnections,
        long connectionAttempts,
        long connectionFailures,
        long sentEnvelopes,
        long failedWrites,
        long receivedEnvelopes,
        long inboundFailures
) {
    public NetworkTransportHealthStats {
        if (transportCount < 0 || activeConnections < 0 || connectionAttempts < 0 || connectionFailures < 0
                || sentEnvelopes < 0 || failedWrites < 0 || receivedEnvelopes < 0 || inboundFailures < 0) {
            throw new IllegalArgumentException("network transport stats must not be negative");
        }
    }

    public static NetworkTransportHealthStats empty() {
        return new NetworkTransportHealthStats(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
