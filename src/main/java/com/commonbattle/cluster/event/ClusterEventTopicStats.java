package com.commonbattle.cluster.event;

/**
 * 单个事件 topic 的中心历史窗口统计。
 */
public record ClusterEventTopicStats(
        String topic,
        int historyLimit,
        int retainedEvents,
        int retainedOwners,
        int subscribers,
        long publishedEvents,
        long droppedEvents,
        long minRetainedRevision,
        long maxRetainedRevision
) {
}
