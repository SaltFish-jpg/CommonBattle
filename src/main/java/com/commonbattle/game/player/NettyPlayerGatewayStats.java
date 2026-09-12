package com.commonbattle.game.player;

/**
 * 玩家客户端 Netty 网关统计。
 */
public record NettyPlayerGatewayStats(
        long acceptedLogins,
        long failedLogins,
        long authRejectedLogins,
        long duplicateRejectedLogins,
        long kickedConnections,
        long acceptedCommands,
        long rejectedCommands,
        long rateLimitedCommands,
        long acceptedHeartbeats,
        long rejectedHeartbeats,
        long rateLimitedHeartbeats,
        long acceptedAcks,
        long rejectedAcks,
        long slowClientClosures,
        long invalidFrames,
        long disconnectedSessions,
        long idleTimeouts
) {
}
