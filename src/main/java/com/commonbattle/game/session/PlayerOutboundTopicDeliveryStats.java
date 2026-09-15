package com.commonbattle.game.session;

/**
 * 单个玩家出站 topic 的投递统计。
 */
public record PlayerOutboundTopicDeliveryStats(
        String topic,
        long pendingOfflineMessages,
        long pendingAckMessages,
        long oldestPendingAckAgeMillis,
        long onlineDeliveries,
        long offlineQueuedDeliveries,
        long droppedDeliveries,
        long coalescedDeliveries,
        long failedOnlineDeliveries,
        long ackedDeliveries
) {
    public PlayerOutboundTopicDeliveryStats {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (pendingOfflineMessages < 0
                || pendingAckMessages < 0
                || oldestPendingAckAgeMillis < 0
                || onlineDeliveries < 0
                || offlineQueuedDeliveries < 0
                || droppedDeliveries < 0
                || coalescedDeliveries < 0
                || failedOnlineDeliveries < 0
                || ackedDeliveries < 0) {
            throw new IllegalArgumentException("topic delivery counters must not be negative");
        }
    }
}
