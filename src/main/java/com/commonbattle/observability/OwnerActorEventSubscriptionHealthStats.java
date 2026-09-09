package com.commonbattle.observability;

import com.commonbattle.game.event.OwnerActorEventSubscriptionStats;

/**
 * owner 关注型 Actor 事件订阅的聚合健康统计。
 */
public record OwnerActorEventSubscriptionHealthStats(
        int subscriptionCount,
        int watchedOwners,
        int watchReferences,
        long watchRequests,
        long unwatchRequests,
        long subscribeRequests,
        long unsubscribeRequests,
        long replayAttempts,
        long replayFailures,
        long repairRequests,
        long repairOwnerCount,
        long repairFailures
) {
    public static OwnerActorEventSubscriptionHealthStats empty() {
        return new OwnerActorEventSubscriptionHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static OwnerActorEventSubscriptionHealthStats from(int subscriptionCount, OwnerActorEventSubscriptionStats stats) {
        return new OwnerActorEventSubscriptionHealthStats(
                subscriptionCount,
                stats.watchedOwners(),
                stats.watchReferences(),
                stats.watchRequests(),
                stats.unwatchRequests(),
                stats.subscribeRequests(),
                stats.unsubscribeRequests(),
                stats.replayAttempts(),
                stats.replayFailures(),
                stats.repairRequests(),
                stats.repairOwnerCount(),
                stats.repairFailures()
        );
    }
}
