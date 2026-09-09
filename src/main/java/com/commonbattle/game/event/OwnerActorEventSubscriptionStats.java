package com.commonbattle.game.event;

/**
 * owner 关注型 Actor 事件订阅统计。
 */
public record OwnerActorEventSubscriptionStats(
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
    public static OwnerActorEventSubscriptionStats empty() {
        return new OwnerActorEventSubscriptionStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public OwnerActorEventSubscriptionStats plus(OwnerActorEventSubscriptionStats other) {
        return new OwnerActorEventSubscriptionStats(
                watchedOwners + other.watchedOwners,
                watchReferences + other.watchReferences,
                watchRequests + other.watchRequests,
                unwatchRequests + other.unwatchRequests,
                subscribeRequests + other.subscribeRequests,
                unsubscribeRequests + other.unsubscribeRequests,
                replayAttempts + other.replayAttempts,
                replayFailures + other.replayFailures,
                repairRequests + other.repairRequests,
                repairOwnerCount + other.repairOwnerCount,
                repairFailures + other.repairFailures
        );
    }
}
