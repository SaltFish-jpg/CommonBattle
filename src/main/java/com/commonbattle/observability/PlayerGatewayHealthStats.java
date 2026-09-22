package com.commonbattle.observability;

import com.commonbattle.game.player.NettyPlayerGatewayStats;
import com.commonbattle.game.session.PlayerClientErrorCode;

import java.util.Map;
import java.util.Objects;

/**
 * 玩家客户端网关健康统计。
 */
public record PlayerGatewayHealthStats(
        int gatewayCount,
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
    public PlayerGatewayHealthStats {
        rejectedCommandsByCode = Map.copyOf(Objects.requireNonNullElse(rejectedCommandsByCode, Map.of()));
    }

    public PlayerGatewayHealthStats(
            int gatewayCount,
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
        this(gatewayCount, acceptedLogins, failedLogins, authRejectedLogins, duplicateRejectedLogins,
                kickedConnections, acceptedCommands, rejectedCommands, rateLimitedCommands, acceptedHeartbeats,
                rejectedHeartbeats, rateLimitedHeartbeats, acceptedAcks, rejectedAcks, slowClientClosures,
                invalidFrames, disconnectedSessions, idleTimeouts, Map.of());
    }

    public static PlayerGatewayHealthStats empty() {
        return new PlayerGatewayHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static PlayerGatewayHealthStats from(int gatewayCount, NettyPlayerGatewayStats stats) {
        return new PlayerGatewayHealthStats(
                gatewayCount,
                stats.acceptedLogins(),
                stats.failedLogins(),
                stats.authRejectedLogins(),
                stats.duplicateRejectedLogins(),
                stats.kickedConnections(),
                stats.acceptedCommands(),
                stats.rejectedCommands(),
                stats.rateLimitedCommands(),
                stats.acceptedHeartbeats(),
                stats.rejectedHeartbeats(),
                stats.rateLimitedHeartbeats(),
                stats.acceptedAcks(),
                stats.rejectedAcks(),
                stats.slowClientClosures(),
                stats.invalidFrames(),
                stats.disconnectedSessions(),
                stats.idleTimeouts(),
                stats.rejectedCommandsByCode()
        );
    }
}
