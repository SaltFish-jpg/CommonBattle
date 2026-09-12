package com.commonbattle.game.session;

/**
 * 玩家出站投递结果。
 */
public record PlayerOutboundDeliveryResult(
        long onlineDeliveries,
        long offlineQueuedDeliveries,
        long droppedDeliveries,
        long failedOnlineDeliveries
) {
    public PlayerOutboundDeliveryResult {
        if (onlineDeliveries < 0 || offlineQueuedDeliveries < 0 || droppedDeliveries < 0 || failedOnlineDeliveries < 0) {
            throw new IllegalArgumentException("delivery counters must not be negative");
        }
    }

    public static PlayerOutboundDeliveryResult empty() {
        return new PlayerOutboundDeliveryResult(0, 0, 0, 0);
    }

    public PlayerOutboundDeliveryResult plus(PlayerOutboundDeliveryResult other) {
        return new PlayerOutboundDeliveryResult(
                onlineDeliveries + other.onlineDeliveries,
                offlineQueuedDeliveries + other.offlineQueuedDeliveries,
                droppedDeliveries + other.droppedDeliveries,
                failedOnlineDeliveries + other.failedOnlineDeliveries
        );
    }
}
