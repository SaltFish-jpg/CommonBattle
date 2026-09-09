package com.commonbattle.observability;

import com.commonbattle.game.event.ActorEventSubscriberStats;

/**
 * 事件到 Actor 邮箱投递器的聚合健康统计。
 */
public record ActorEventSubscriberHealthStats(
        int subscriberCount,
        long receivedEvents,
        long enqueuedEvents,
        long rejectedEvents,
        long handledEvents,
        long failedEvents
) {
    public static ActorEventSubscriberHealthStats empty() {
        return new ActorEventSubscriberHealthStats(0, 0, 0, 0, 0, 0);
    }

    public static ActorEventSubscriberHealthStats from(int subscriberCount, ActorEventSubscriberStats stats) {
        return new ActorEventSubscriberHealthStats(
                subscriberCount,
                stats.receivedEvents(),
                stats.enqueuedEvents(),
                stats.rejectedEvents(),
                stats.handledEvents(),
                stats.failedEvents()
        );
    }
}
