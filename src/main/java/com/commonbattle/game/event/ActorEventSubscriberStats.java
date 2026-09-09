package com.commonbattle.game.event;

/**
 * 事件投递到 Actor 邮箱的运行统计。
 */
public record ActorEventSubscriberStats(
        long receivedEvents,
        long enqueuedEvents,
        long rejectedEvents,
        long handledEvents,
        long failedEvents
) {
    public static ActorEventSubscriberStats empty() {
        return new ActorEventSubscriberStats(0, 0, 0, 0, 0);
    }

    public ActorEventSubscriberStats plus(ActorEventSubscriberStats other) {
        return new ActorEventSubscriberStats(
                receivedEvents + other.receivedEvents,
                enqueuedEvents + other.enqueuedEvents,
                rejectedEvents + other.rejectedEvents,
                handledEvents + other.handledEvents,
                failedEvents + other.failedEvents
        );
    }
}
