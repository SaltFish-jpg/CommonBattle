package com.commonbattle.observability;

import java.util.Map;

/**
 * 事件中心历史窗口健康统计。
 */
public record EventCenterHealthStats(
        int centerCount,
        int topicCount,
        int retainedEvents,
        int retainedOwners,
        int subscribers,
        long publishedEvents,
        long droppedEvents,
        Map<String, EventCenterTopicHealthStats> topics
) {
    public EventCenterHealthStats {
        topics = Map.copyOf(topics);
    }

    public EventCenterHealthStats(
            int centerCount,
            int topicCount,
            int retainedEvents,
            int retainedOwners,
            int subscribers,
            long publishedEvents,
            long droppedEvents
    ) {
        this(centerCount, topicCount, retainedEvents, retainedOwners, subscribers, publishedEvents, droppedEvents,
                Map.of());
    }

    public static EventCenterHealthStats empty() {
        return new EventCenterHealthStats(0, 0, 0, 0, 0, 0, 0);
    }
}
