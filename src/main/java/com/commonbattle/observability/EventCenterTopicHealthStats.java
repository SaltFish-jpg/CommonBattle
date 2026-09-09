package com.commonbattle.observability;

/**
 * 事件中心单 topic 历史窗口健康统计。
 */
public record EventCenterTopicHealthStats(
        String topic,
        int historyLimit,
        int retainedEvents,
        int retainedOwners,
        int subscribers,
        long publishedEvents,
        long droppedEvents,
        long deliveryFailures,
        long expiredSubscriptions,
        long minRetainedRevision,
        long maxRetainedRevision
) {
}
