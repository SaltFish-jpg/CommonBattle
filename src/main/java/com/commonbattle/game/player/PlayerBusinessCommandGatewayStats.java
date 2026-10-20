package com.commonbattle.game.player;

/**
 * 玩家业务命令网关统计。
 * 用于观察跨 Game 服 owner 转发是否正常返回，以及超时后迟到回包是否被丢弃。
 */
public record PlayerBusinessCommandGatewayStats(
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
    public static PlayerBusinessCommandGatewayStats empty() {
        return new PlayerBusinessCommandGatewayStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public PlayerBusinessCommandGatewayStats(
            long remoteForwards,
            long remoteForwardSuccessResponses,
            long remoteForwardFailureResponses,
            long remoteForwardTimedOutResponses,
            long lateRemoteForwardResponses
    ) {
        this(remoteForwards, remoteForwardSuccessResponses, remoteForwardFailureResponses,
                remoteForwardTimedOutResponses, lateRemoteForwardResponses, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public PlayerBusinessCommandGatewayStats(
            long remoteForwards,
            long remoteForwardSuccessResponses,
            long remoteForwardFailureResponses,
            long remoteForwardTimedOutResponses,
            long lateRemoteForwardResponses,
            long remoteForwardMigrationProtectedResponses
    ) {
        this(remoteForwards, remoteForwardSuccessResponses, remoteForwardFailureResponses,
                remoteForwardTimedOutResponses, lateRemoteForwardResponses, remoteForwardMigrationProtectedResponses, 0,
                0, 0, 0, 0, 0, 0, 0);
    }

    public PlayerBusinessCommandGatewayStats(
            long remoteForwards,
            long remoteForwardSuccessResponses,
            long remoteForwardFailureResponses,
            long remoteForwardTimedOutResponses,
            long lateRemoteForwardResponses,
            long remoteForwardMigrationProtectedResponses,
            long remoteForwardRedirectResponses
    ) {
        this(remoteForwards, remoteForwardSuccessResponses, remoteForwardFailureResponses,
                remoteForwardTimedOutResponses, lateRemoteForwardResponses, remoteForwardMigrationProtectedResponses,
                remoteForwardRedirectResponses, 0, 0, 0, 0, 0, 0, 0);
    }

    public PlayerBusinessCommandGatewayStats(
            long remoteForwards,
            long remoteForwardSuccessResponses,
            long remoteForwardFailureResponses,
            long remoteForwardTimedOutResponses,
            long lateRemoteForwardResponses,
            long remoteForwardMigrationProtectedResponses,
            long remoteForwardRedirectResponses,
            long remoteForwardRedirectRetries
    ) {
        this(remoteForwards, remoteForwardSuccessResponses, remoteForwardFailureResponses,
                remoteForwardTimedOutResponses, lateRemoteForwardResponses, remoteForwardMigrationProtectedResponses,
                remoteForwardRedirectResponses, remoteForwardRedirectRetries, 0, 0, 0, 0, 0, 0);
    }

    public PlayerBusinessCommandGatewayStats(
            long remoteForwards,
            long remoteForwardSuccessResponses,
            long remoteForwardFailureResponses,
            long remoteForwardTimedOutResponses,
            long lateRemoteForwardResponses,
            long remoteForwardMigrationProtectedResponses,
            long remoteForwardRedirectResponses,
            long remoteForwardRedirectRetries,
            long remoteForwardRedirectRouteRefreshes,
            long remoteForwardRedirectRouteRefreshFailures
    ) {
        this(remoteForwards, remoteForwardSuccessResponses, remoteForwardFailureResponses,
                remoteForwardTimedOutResponses, lateRemoteForwardResponses, remoteForwardMigrationProtectedResponses,
                remoteForwardRedirectResponses, remoteForwardRedirectRetries, remoteForwardRedirectRouteRefreshes,
                remoteForwardRedirectRouteRefreshFailures, 0, 0, 0, 0);
    }

    public PlayerBusinessCommandGatewayStats plus(PlayerBusinessCommandGatewayStats other) {
        return new PlayerBusinessCommandGatewayStats(
                remoteForwards + other.remoteForwards,
                remoteForwardSuccessResponses + other.remoteForwardSuccessResponses,
                remoteForwardFailureResponses + other.remoteForwardFailureResponses,
                remoteForwardTimedOutResponses + other.remoteForwardTimedOutResponses,
                lateRemoteForwardResponses + other.lateRemoteForwardResponses,
                remoteForwardMigrationProtectedResponses + other.remoteForwardMigrationProtectedResponses,
                remoteForwardRedirectResponses + other.remoteForwardRedirectResponses,
                remoteForwardRedirectRetries + other.remoteForwardRedirectRetries,
                remoteForwardRedirectRouteRefreshes + other.remoteForwardRedirectRouteRefreshes,
                remoteForwardRedirectRouteRefreshFailures + other.remoteForwardRedirectRouteRefreshFailures,
                remoteForwardTimeoutRecoveryQueries + other.remoteForwardTimeoutRecoveryQueries,
                remoteForwardTimeoutRecoveryHits + other.remoteForwardTimeoutRecoveryHits,
                remoteForwardTimeoutRecoveryMisses + other.remoteForwardTimeoutRecoveryMisses,
                remoteForwardTimeoutRecoveryFailures + other.remoteForwardTimeoutRecoveryFailures
        );
    }
}
