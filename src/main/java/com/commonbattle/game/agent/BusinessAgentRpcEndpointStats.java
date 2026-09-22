package com.commonbattle.game.agent;

/**
 * 业务 Agent RPC 端点运行统计。
 * 用于观察幂等缓存命中、进行中请求合并、完成态回放、TTL 过期和容量淘汰。
 */
public record BusinessAgentRpcEndpointStats(
        int endpointCount,
        int idempotencyCacheCapacity,
        long idempotencyCacheTtlMillis,
        int idempotencyCacheSize,
        long idempotencyStartedRequests,
        long idempotencyJoinedInFlightRequests,
        long idempotencyCompletedCacheHits,
        long idempotencyExpiredEntries,
        long idempotencyEvictedEntries,
        long idempotencyCompletedSuccesses,
        long idempotencyCompletedFailures
) {
    public static BusinessAgentRpcEndpointStats empty() {
        return new BusinessAgentRpcEndpointStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public BusinessAgentRpcEndpointStats plus(BusinessAgentRpcEndpointStats other) {
        return new BusinessAgentRpcEndpointStats(
                endpointCount + other.endpointCount,
                Math.max(idempotencyCacheCapacity, other.idempotencyCacheCapacity),
                Math.max(idempotencyCacheTtlMillis, other.idempotencyCacheTtlMillis),
                idempotencyCacheSize + other.idempotencyCacheSize,
                idempotencyStartedRequests + other.idempotencyStartedRequests,
                idempotencyJoinedInFlightRequests + other.idempotencyJoinedInFlightRequests,
                idempotencyCompletedCacheHits + other.idempotencyCompletedCacheHits,
                idempotencyExpiredEntries + other.idempotencyExpiredEntries,
                idempotencyEvictedEntries + other.idempotencyEvictedEntries,
                idempotencyCompletedSuccesses + other.idempotencyCompletedSuccesses,
                idempotencyCompletedFailures + other.idempotencyCompletedFailures
        );
    }
}
