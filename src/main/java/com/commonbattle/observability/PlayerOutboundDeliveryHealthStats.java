package com.commonbattle.observability;

import com.commonbattle.game.session.PlayerOutboundDeliveryStats;

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
        long ackedDeliveries
) {
    public static PlayerOutboundDeliveryHealthStats empty() {
        return new PlayerOutboundDeliveryHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
                stats.ackedDeliveries()
        );
    }
}
