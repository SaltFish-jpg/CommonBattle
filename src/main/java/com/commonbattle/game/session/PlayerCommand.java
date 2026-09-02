package com.commonbattle.game.session;

import java.util.Objects;

/**
 * 客户端请求解码后的玩家命令。
 * sequence 是同一会话内严格递增的请求序号，用于去重和乱序保护。
 */
public record PlayerCommand(
        long playerId,
        String sessionId,
        long sessionEpoch,
        long sequence,
        String operation,
        Object payload
) {
    public PlayerCommand {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(operation, "operation");
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
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
}
