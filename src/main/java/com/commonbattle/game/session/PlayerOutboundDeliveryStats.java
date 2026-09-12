package com.commonbattle.game.session;

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
        long ackedDeliveries
) {
    public static PlayerOutboundDeliveryStats empty() {
        return new PlayerOutboundDeliveryStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
