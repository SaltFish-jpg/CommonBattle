package com.commonbattle.observability;

import com.commonbattle.game.player.PlayerBusinessCommandGatewayStats;

/**
 * 玩家业务命令网关健康统计。
 */
public record PlayerBusinessCommandGatewayHealthStats(
        int gatewayCount,
        long remoteForwards,
        long remoteForwardSuccessResponses,
        long remoteForwardFailureResponses,
        long remoteForwardTimedOutResponses,
        long lateRemoteForwardResponses,
        long remoteForwardMigrationProtectedResponses
) {
    public static PlayerBusinessCommandGatewayHealthStats empty() {
        return new PlayerBusinessCommandGatewayHealthStats(0, 0, 0, 0, 0, 0, 0);
    }

    public static PlayerBusinessCommandGatewayHealthStats from(int gatewayCount, PlayerBusinessCommandGatewayStats stats) {
        return new PlayerBusinessCommandGatewayHealthStats(
                gatewayCount,
                stats.remoteForwards(),
                stats.remoteForwardSuccessResponses(),
                stats.remoteForwardFailureResponses(),
                stats.remoteForwardTimedOutResponses(),
                stats.lateRemoteForwardResponses(),
                stats.remoteForwardMigrationProtectedResponses()
        );
    }
}
