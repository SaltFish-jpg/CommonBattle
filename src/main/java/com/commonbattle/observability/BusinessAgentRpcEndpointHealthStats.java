package com.commonbattle.observability;

import com.commonbattle.game.agent.BusinessAgentRpcEndpointStats;

/**
 * 业务 Agent RPC 端点健康统计。
 */
public record BusinessAgentRpcEndpointHealthStats(
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
    public static BusinessAgentRpcEndpointHealthStats empty() {
        return from(BusinessAgentRpcEndpointStats.empty());
    }

    public static BusinessAgentRpcEndpointHealthStats from(BusinessAgentRpcEndpointStats stats) {
        return new BusinessAgentRpcEndpointHealthStats(
                stats.endpointCount(),
                stats.idempotencyCacheCapacity(),
                stats.idempotencyCacheTtlMillis(),
                stats.idempotencyCacheSize(),
                stats.idempotencyStartedRequests(),
                stats.idempotencyJoinedInFlightRequests(),
                stats.idempotencyCompletedCacheHits(),
                stats.idempotencyExpiredEntries(),
                stats.idempotencyEvictedEntries(),
                stats.idempotencyCompletedSuccesses(),
                stats.idempotencyCompletedFailures()
        );
    }

    public BusinessAgentRpcEndpointHealthStats plus(BusinessAgentRpcEndpointHealthStats other) {
        return new BusinessAgentRpcEndpointHealthStats(
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
