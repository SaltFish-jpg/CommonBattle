package com.commonbattle.game.session;

import java.util.Objects;

/**
 * 客户端对服务端出站消息的累计确认。
 * 客户端收到 sequence 小于等于 acknowledgedSequence 的消息后，可用当前会话身份发送一次确认。
 */
public record PlayerClientOutboundAck(
        long playerId,
        String sessionId,
        long sessionEpoch,
        long acknowledgedSequence
) {
    public PlayerClientOutboundAck {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        sessionId = Objects.requireNonNull(sessionId, "sessionId").trim();
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (sessionEpoch <= 0) {
            throw new IllegalArgumentException("sessionEpoch must be positive");
        }
        if (acknowledgedSequence <= 0) {
            throw new IllegalArgumentException("acknowledgedSequence must be positive");
        }
    }
}
