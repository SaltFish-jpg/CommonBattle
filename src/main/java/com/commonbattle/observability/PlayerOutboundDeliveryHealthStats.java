package com.commonbattle.observability;

import com.commonbattle.game.session.PlayerOutboundDeliveryStats;
import com.commonbattle.game.session.PlayerOutboundTopicDeliveryStats;

import java.util.Map;

/**
 * 玩家出站投递层健康统计。
 */
public record PlayerOutboundDeliveryHealthStats(
        int runtimeCount,
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
    public PlayerOutboundDeliveryHealthStats(
            int runtimeCount,
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
        this(runtimeCount, activeConnections, offlinePlayers, pendingOfflineMessages, pendingAckPlayers,
                pendingAckMessages, oldestPendingAckAgeMillis, onlineDeliveries, offlineQueuedDeliveries,
                droppedDeliveries, coalescedDeliveries, failedOnlineDeliveries, ackedDeliveries, Map.of());
    }

    public PlayerOutboundDeliveryHealthStats {
        topics = Map.copyOf(topics);
    }

    public static PlayerOutboundDeliveryHealthStats empty() {
        return new PlayerOutboundDeliveryHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());
    }

    public static PlayerOutboundDeliveryHealthStats from(int runtimeCount, PlayerOutboundDeliveryStats stats) {
        return new PlayerOutboundDeliveryHealthStats(
                runtimeCount,
                stats.activeConnections(),
                stats.offlinePlayers(),
                stats.pendingOfflineMessages(),
                stats.pendingAckPlayers(),
                stats.pendingAckMessages(),
                stats.oldestPendingAckAgeMillis(),
                stats.onlineDeliveries(),
                stats.offlineQueuedDeliveries(),
                stats.droppedDeliveries(),
                stats.coalescedDeliveries(),
                stats.failedOnlineDeliveries(),
                stats.ackedDeliveries(),
                stats.topics()
        );
    }
}
