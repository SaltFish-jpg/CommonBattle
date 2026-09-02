package com.commonbattle.observability;

/**
 * 跨服事件订阅恢复健康统计。
 */
public record EventSubscriptionHealthStats(
        int managerCount,
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
    public static EventSubscriptionHealthStats empty() {
        return new EventSubscriptionHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
