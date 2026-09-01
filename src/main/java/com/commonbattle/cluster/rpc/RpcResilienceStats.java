package com.commonbattle.cluster.rpc;

/**
 * RPC 韧性装饰器统计。
 */
public record RpcResilienceStats(
        long attempts,
        long retries,
        long shortCircuited,
        long openedCircuits
) {
}
