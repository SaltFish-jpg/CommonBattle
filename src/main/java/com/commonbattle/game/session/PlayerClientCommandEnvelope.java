package com.commonbattle.game.session;

import java.util.Objects;

/**
 * 玩家客户端入站命令 wire 信封。
 * Netty 入站线程只把它解码成 PlayerCommand 并提交，不直接执行业务。
 */
public record PlayerClientCommandEnvelope(
        long playerId,
        String sessionId,
        long sessionEpoch,
        long sequence,
        String operation,
        Object payload
) {
    public PlayerClientCommandEnvelope {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        sessionId = Objects.requireNonNull(sessionId, "sessionId").trim();
        operation = Objects.requireNonNull(operation, "operation").trim();
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (sessionEpoch <= 0) {
            throw new IllegalArgumentException("sessionEpoch must be positive");
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
    }

    public PlayerCommand toCommand() {
        return new PlayerCommand(playerId, sessionId, sessionEpoch, sequence, operation, payload);
    }
}
