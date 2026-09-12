package com.commonbattle.game.player;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家客户端网关服务级指标。
 */
final class NettyPlayerGatewayMetrics {
    private final AtomicLong acceptedLogins = new AtomicLong();
    private final AtomicLong failedLogins = new AtomicLong();
    private final AtomicLong authRejectedLogins = new AtomicLong();
    private final AtomicLong duplicateRejectedLogins = new AtomicLong();
    private final AtomicLong kickedConnections = new AtomicLong();
    private final AtomicLong acceptedCommands = new AtomicLong();
    private final AtomicLong rejectedCommands = new AtomicLong();
    private final AtomicLong rateLimitedCommands = new AtomicLong();
    private final AtomicLong acceptedHeartbeats = new AtomicLong();
    private final AtomicLong rejectedHeartbeats = new AtomicLong();
    private final AtomicLong rateLimitedHeartbeats = new AtomicLong();
    private final AtomicLong acceptedAcks = new AtomicLong();
    private final AtomicLong rejectedAcks = new AtomicLong();
    private final AtomicLong slowClientClosures = new AtomicLong();
    private final AtomicLong invalidFrames = new AtomicLong();
    private final AtomicLong disconnectedSessions = new AtomicLong();
    private final AtomicLong idleTimeouts = new AtomicLong();

    void acceptedLogin() {
        acceptedLogins.incrementAndGet();
    }

    void failedLogin() {
        failedLogins.incrementAndGet();
    }

    void authRejectedLogin() {
        authRejectedLogins.incrementAndGet();
    }

    void duplicateRejectedLogin() {
        duplicateRejectedLogins.incrementAndGet();
    }

    void kickedConnection() {
        kickedConnections.incrementAndGet();
    }

    void acceptedCommand() {
        acceptedCommands.incrementAndGet();
    }

    void rejectedCommand() {
        rejectedCommands.incrementAndGet();
    }

    void rateLimitedCommand() {
        rateLimitedCommands.incrementAndGet();
    }

    void acceptedHeartbeat() {
        acceptedHeartbeats.incrementAndGet();
    }

    void rejectedHeartbeat() {
        rejectedHeartbeats.incrementAndGet();
    }

    void rateLimitedHeartbeat() {
        rateLimitedHeartbeats.incrementAndGet();
    }

    void acceptedAck() {
        acceptedAcks.incrementAndGet();
    }

    void rejectedAck() {
        rejectedAcks.incrementAndGet();
    }

    void slowClientClosure() {
        slowClientClosures.incrementAndGet();
    }

    void invalidFrame() {
        invalidFrames.incrementAndGet();
    }

    void disconnectedSession() {
        disconnectedSessions.incrementAndGet();
    }

    void idleTimeout() {
        idleTimeouts.incrementAndGet();
    }

    NettyPlayerGatewayStats snapshot() {
        return new NettyPlayerGatewayStats(
                acceptedLogins.get(),
                failedLogins.get(),
                authRejectedLogins.get(),
                duplicateRejectedLogins.get(),
                kickedConnections.get(),
                acceptedCommands.get(),
                rejectedCommands.get(),
                rateLimitedCommands.get(),
                acceptedHeartbeats.get(),
                rejectedHeartbeats.get(),
                rateLimitedHeartbeats.get(),
                acceptedAcks.get(),
                rejectedAcks.get(),
                slowClientClosures.get(),
                invalidFrames.get(),
                disconnectedSessions.get(),
                idleTimeouts.get()
        );
    }
}
