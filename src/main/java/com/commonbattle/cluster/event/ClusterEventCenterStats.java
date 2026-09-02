package com.commonbattle.cluster.event;

import java.util.Map;

/**
 * 事件中心历史窗口统计。
 */
public record ClusterEventCenterStats(Map<String, ClusterEventTopicStats> topics) {
    public ClusterEventCenterStats {
        topics = Map.copyOf(topics);
    }

    public long totalPublishedEvents() {
        return topics.values().stream().mapToLong(ClusterEventTopicStats::publishedEvents).sum();
    }

    public long totalDroppedEvents() {
        return topics.values().stream().mapToLong(ClusterEventTopicStats::droppedEvents).sum();
    }

    public int totalRetainedEvents() {
        return topics.values().stream().mapToInt(ClusterEventTopicStats::retainedEvents).sum();
    }
}
