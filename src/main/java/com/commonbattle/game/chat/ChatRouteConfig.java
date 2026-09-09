package com.commonbattle.game.chat;

/**
 * Chat 业务路由配置。
 */
public record ChatRouteConfig(int worldShardCount) {
    public ChatRouteConfig {
        if (worldShardCount <= 0) {
            throw new IllegalArgumentException("worldShardCount must be positive");
        }
    }

    public static ChatRouteConfig defaults() {
        return new ChatRouteConfig(8);
    }
}
