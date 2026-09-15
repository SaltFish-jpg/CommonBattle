package com.commonbattle.game.session;

import java.util.Map;

/**
 * 玩家出站投递层累计统计。
 */
public record PlayerOutboundDeliveryStats(
        long activeConnections,
        long offlinePlayers,
        long pendingOfflineMessages,
        long pendingAckPlayers,
        long pendingAckMessages,
        long oldestPendingAckAgeMillis,
        long onlineDeliveries,
        long offlineQueuedDeliveries,
        long droppedDeliveries,
        long coalescedDeliveries,
        long failedOnlineDeliveries,
        long ackedDeliveries,
        Map<String, PlayerOutboundTopicDeliveryStats> topics
) {
    public PlayerOutboundDeliveryStats(
            long activeConnections,
            long offlinePlayers,
            long pendingOfflineMessages,
            long pendingAckPlayers,
            long pendingAckMessages,
            long oldestPendingAckAgeMillis,
            long onlineDeliveries,
            long offlineQueuedDeliveries,
            long droppedDeliveries,
            long coalescedDeliveries,
            long failedOnlineDeliveries,
            long ackedDeliveries
    ) {
        this(activeConnections, offlinePlayers, pendingOfflineMessages, pendingAckPlayers, pendingAckMessages,
                oldestPendingAckAgeMillis, onlineDeliveries, offlineQueuedDeliveries, droppedDeliveries,
                coalescedDeliveries, failedOnlineDeliveries, ackedDeliveries, Map.of());
    }

    public PlayerOutboundDeliveryStats {
        topics = Map.copyOf(topics);
    }

    public static PlayerOutboundDeliveryStats empty() {
        return new PlayerOutboundDeliveryStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());
    }
}
