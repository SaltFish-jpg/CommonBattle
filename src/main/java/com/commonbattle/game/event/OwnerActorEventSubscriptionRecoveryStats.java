package com.commonbattle.game.event;

/**
 * owner 关注型事件订阅恢复调度统计。
 */
public record OwnerActorEventSubscriptionRecoveryStats(
        long runs,
        long succeededRuns,
        long failedRuns,
        long skippedRuns,
        long recoveredSubscriptions,
        long failedSubscriptions,
        boolean inFlight
) {
    public OwnerActorEventSubscriptionRecoveryStats {
        if (runs < 0
                || succeededRuns < 0
                || failedRuns < 0
                || skippedRuns < 0
                || recoveredSubscriptions < 0
                || failedSubscriptions < 0) {
            throw new IllegalArgumentException("owner event subscription recovery stats must not be negative");
        }
    }

    public static OwnerActorEventSubscriptionRecoveryStats empty() {
        return new OwnerActorEventSubscriptionRecoveryStats(0, 0, 0, 0, 0, 0, false);
    }
}
