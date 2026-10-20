package com.commonbattle.game.agent;

/**
 * 本服业务 Agent 请求幂等缓存统计。
 */
public record BusinessAgentLocalIdempotencyStats(
        int capacity,
        long ttlMillis,
        int size,
        long startedRequests,
        long joinedInFlightRequests,
        long completedCacheHits,
        long expiredEntries,
        long evictedEntries,
        long completedSuccesses,
        long completedFailures,
        long abandonedInFlightRequests
) {
    public static BusinessAgentLocalIdempotencyStats empty() {
        return new BusinessAgentLocalIdempotencyStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
