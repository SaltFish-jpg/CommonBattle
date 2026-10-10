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
        long lateRemoteForwardResponses
) {
    public static PlayerBusinessCommandGatewayStats empty() {
        return new PlayerBusinessCommandGatewayStats(0, 0, 0, 0, 0);
    }

    public PlayerBusinessCommandGatewayStats plus(PlayerBusinessCommandGatewayStats other) {
        return new PlayerBusinessCommandGatewayStats(
                remoteForwards + other.remoteForwards,
                remoteForwardSuccessResponses + other.remoteForwardSuccessResponses,
                remoteForwardFailureResponses + other.remoteForwardFailureResponses,
                remoteForwardTimedOutResponses + other.remoteForwardTimedOutResponses,
                lateRemoteForwardResponses + other.lateRemoteForwardResponses
        );
    }
}
