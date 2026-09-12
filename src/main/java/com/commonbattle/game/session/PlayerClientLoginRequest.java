package com.commonbattle.game.session;

import java.util.Objects;

/**
 * 玩家客户端登录请求。
 */
public record PlayerClientLoginRequest(long playerId, String sessionId, String token) {
    public PlayerClientLoginRequest(long playerId, String sessionId) {
        this(playerId, sessionId, "");
    }

    public PlayerClientLoginRequest {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        sessionId = Objects.requireNonNull(sessionId, "sessionId").trim();
        token = Objects.requireNonNullElse(token, "").trim();
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }
}
