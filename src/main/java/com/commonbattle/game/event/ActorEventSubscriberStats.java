package com.commonbattle.game.event;

/**
 * 事件投递到 Actor 邮箱的运行统计。
 */
public record ActorEventSubscriberStats(
        long receivedEvents,
        long enqueuedEvents,
        long rejectedEvents,
        long handledEvents,
        long failedEvents,
        long duplicateEvents,
        long retryScheduledEvents,
        long deadLetteredEvents,
        int pendingInboxEvents,
        int inboxDeadLetters
) {
    public ActorEventSubscriberStats(
            long receivedEvents,
            long enqueuedEvents,
            long rejectedEvents,
            long handledEvents,
            long failedEvents
    ) {
        this(receivedEvents, enqueuedEvents, rejectedEvents, handledEvents, failedEvents, 0, 0, 0, 0, 0);
    }

    public static ActorEventSubscriberStats empty() {
        return new ActorEventSubscriberStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public ActorEventSubscriberStats plus(ActorEventSubscriberStats other) {
        return new ActorEventSubscriberStats(
                receivedEvents + other.receivedEvents,
                enqueuedEvents + other.enqueuedEvents,
                rejectedEvents + other.rejectedEvents,
                handledEvents + other.handledEvents,
                failedEvents + other.failedEvents,
                duplicateEvents + other.duplicateEvents,
                retryScheduledEvents + other.retryScheduledEvents,
                deadLetteredEvents + other.deadLetteredEvents,
                pendingInboxEvents + other.pendingInboxEvents,
                inboxDeadLetters + other.inboxDeadLetters
        );
    }
}
