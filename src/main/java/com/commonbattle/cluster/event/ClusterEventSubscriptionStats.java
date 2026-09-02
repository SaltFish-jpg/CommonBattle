package com.commonbattle.cluster.event;

/**
 * 跨服事件订阅恢复统计。
 */
public record ClusterEventSubscriptionStats(
        int registered,
        int active,
        long subscribeAttempts,
        long subscribeFailures,
        long replayAttempts,
        long replayFailures,
        long replayDelivered,
        long replayUnavailableOwners,
        long replayRepairRequests,
        long replayRepairOwnerCount,
        long replayRepairFailures,
        long cursorFailures
) {
}
