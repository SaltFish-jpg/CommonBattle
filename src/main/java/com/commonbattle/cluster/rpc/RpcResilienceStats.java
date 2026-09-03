package com.commonbattle.cluster.rpc;

/**
 * RPC 韧性装饰器统计。
 */
public record RpcResilienceStats(
        long attempts,
        long retries,
        long shortCircuited,
        long openedCircuits,
        long rejectedAfterClose,
        int circuits,
        int openCircuits
) {
    public static RpcResilienceStats empty() {
        return new RpcResilienceStats(0, 0, 0, 0, 0, 0, 0);
    }

    public RpcResilienceStats plus(RpcResilienceStats other) {
        return new RpcResilienceStats(
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
