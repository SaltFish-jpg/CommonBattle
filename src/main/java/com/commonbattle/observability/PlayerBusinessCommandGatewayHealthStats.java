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
        long remoteForwardMigrationProtectedResponses,
        long remoteForwardRedirectResponses,
        long remoteForwardRedirectRetries,
        long remoteForwardRedirectRouteRefreshes,
        long remoteForwardRedirectRouteRefreshFailures,
        long remoteForwardTimeoutRecoveryQueries,
        long remoteForwardTimeoutRecoveryHits,
        long remoteForwardTimeoutRecoveryMisses,
        long remoteForwardTimeoutRecoveryFailures
) {
    public static PlayerBusinessCommandGatewayHealthStats empty() {
        return new PlayerBusinessCommandGatewayHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static PlayerBusinessCommandGatewayHealthStats from(int gatewayCount, PlayerBusinessCommandGatewayStats stats) {
        return new PlayerBusinessCommandGatewayHealthStats(
                gatewayCount,
                stats.remoteForwards(),
                stats.remoteForwardSuccessResponses(),
                stats.remoteForwardFailureResponses(),
                stats.remoteForwardTimedOutResponses(),
                stats.lateRemoteForwardResponses(),
                stats.remoteForwardMigrationProtectedResponses(),
                stats.remoteForwardRedirectResponses(),
                stats.remoteForwardRedirectRetries(),
                stats.remoteForwardRedirectRouteRefreshes(),
                stats.remoteForwardRedirectRouteRefreshFailures(),
                stats.remoteForwardTimeoutRecoveryQueries(),
                stats.remoteForwardTimeoutRecoveryHits(),
                stats.remoteForwardTimeoutRecoveryMisses(),
                stats.remoteForwardTimeoutRecoveryFailures()
        );
    }
}
