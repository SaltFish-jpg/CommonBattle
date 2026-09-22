package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerClientErrorCode;

import java.util.Map;
import java.util.Objects;

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
        long idleTimeouts,
        Map<PlayerClientErrorCode, Long> rejectedCommandsByCode
) {
    public NettyPlayerGatewayStats {
        rejectedCommandsByCode = Map.copyOf(Objects.requireNonNullElse(rejectedCommandsByCode, Map.of()));
    }

    public NettyPlayerGatewayStats(
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
        this(acceptedLogins, failedLogins, authRejectedLogins, duplicateRejectedLogins, kickedConnections,
                acceptedCommands, rejectedCommands, rateLimitedCommands, acceptedHeartbeats, rejectedHeartbeats,
                rateLimitedHeartbeats, acceptedAcks, rejectedAcks, slowClientClosures, invalidFrames,
                disconnectedSessions, idleTimeouts, Map.of());
    }
}
