package com.commonbattle.observability;

import com.commonbattle.cluster.rpc.RpcResilienceStats;

/**
 * RPC 韧性治理健康统计。
 * 聚合重试、熔断、短路和关闭后拒绝等跨服调用保护指标。
 */
public record RpcResilienceHealthStats(
        long attempts,
        long retries,
        long shortCircuited,
        long openedCircuits,
        long rejectedAfterClose,
        int circuits,
        int openCircuits
) {
    public static RpcResilienceHealthStats empty() {
        return from(RpcResilienceStats.empty());
    }

    static RpcResilienceHealthStats from(RpcResilienceStats stats) {
        return new RpcResilienceHealthStats(
                stats.attempts(),
                stats.retries(),
                stats.shortCircuited(),
                stats.openedCircuits(),
                stats.rejectedAfterClose(),
                stats.circuits(),
                stats.openCircuits()
        );
    }

    RpcResilienceHealthStats plus(RpcResilienceHealthStats other) {
        return new RpcResilienceHealthStats(
                attempts + other.attempts,
                retries + other.retries,
                shortCircuited + other.shortCircuited,
                openedCircuits + other.openedCircuits,
                rejectedAfterClose + other.rejectedAfterClose,
                circuits + other.circuits,
                openCircuits + other.openCircuits
        );
    }
}
