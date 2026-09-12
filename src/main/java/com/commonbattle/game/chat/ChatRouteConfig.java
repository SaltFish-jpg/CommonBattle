package com.commonbattle.game.chat;

/**
 * Chat 业务路由配置。
 */
public record ChatRouteConfig(
        int worldShardCount,
        int maxHistoryMessages,
        int maxPendingDeliveriesPerRecipient,
        ChatDeliveryOverflowStrategy deliveryOverflowStrategy
) {
    public static final int DEFAULT_WORLD_SHARDS = 8;
    public static final int DEFAULT_MAX_HISTORY_MESSAGES = 100;
    public static final int DEFAULT_MAX_PENDING_DELIVERIES_PER_RECIPIENT = 256;

    public ChatRouteConfig(int worldShardCount) {
        this(worldShardCount, DEFAULT_MAX_HISTORY_MESSAGES);
    }

    public ChatRouteConfig(int worldShardCount, int maxHistoryMessages) {
        this(worldShardCount, maxHistoryMessages, DEFAULT_MAX_PENDING_DELIVERIES_PER_RECIPIENT,
                ChatDeliveryOverflowStrategy.DROP_OLDEST);
    }

    public ChatRouteConfig {
        if (worldShardCount <= 0) {
            throw new IllegalArgumentException("worldShardCount must be positive");
        }
        if (maxHistoryMessages <= 0) {
            throw new IllegalArgumentException("maxHistoryMessages must be positive");
        }
        if (maxPendingDeliveriesPerRecipient <= 0) {
            throw new IllegalArgumentException("maxPendingDeliveriesPerRecipient must be positive");
        }
        java.util.Objects.requireNonNull(deliveryOverflowStrategy, "deliveryOverflowStrategy");
    }

    public static ChatRouteConfig defaults() {
        return new ChatRouteConfig(
                DEFAULT_WORLD_SHARDS,
                DEFAULT_MAX_HISTORY_MESSAGES,
                DEFAULT_MAX_PENDING_DELIVERIES_PER_RECIPIENT,
                ChatDeliveryOverflowStrategy.DROP_OLDEST
        );
    }
}
