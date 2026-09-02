package com.commonbattle.game.session;

import java.time.Instant;
import java.util.Objects;

/**
 * 玩家当前客户端会话。
 * epoch 每次重连递增，用于拒绝旧连接迟到的请求。
 */
public record PlayerSession(long playerId, String sessionId, long epoch, Instant boundAt) {
    public PlayerSession {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(boundAt, "boundAt");
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (epoch <= 0) {
            throw new IllegalArgumentException("epoch must be positive");
        }
    }
}
