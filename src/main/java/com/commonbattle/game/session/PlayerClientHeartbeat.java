package com.commonbattle.game.session;

import java.util.Objects;

/**
 * 玩家客户端心跳请求。
 */
public record PlayerClientHeartbeat(long playerId, String sessionId, long sessionEpoch, long sequence) {
    public PlayerClientHeartbeat {
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
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }
}
