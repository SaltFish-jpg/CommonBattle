package com.commonbattle.game.session;

/**
 * 玩家客户端心跳确认。
 */
public record PlayerClientHeartbeatAck(
        long playerId,
        String sessionId,
        long sessionEpoch,
        long sequence,
        String status
) {
}
