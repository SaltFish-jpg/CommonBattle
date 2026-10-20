package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerCommand;

import java.util.Objects;

/**
 * 玩家业务命令结果查询。
 * 用于跨服调用方在回包丢失或重试恢复时，只查询 owner 端已完成响应缓存，不重新执行业务命令。
 */
public record PlayerBusinessResponseQuery(
        long playerId,
        String sessionId,
        long sessionEpoch,
        long sequence,
        String operation
) {
    public PlayerBusinessResponseQuery {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        operation = Objects.requireNonNull(operation, "operation");
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

    public static PlayerBusinessResponseQuery from(PlayerCommand command) {
        Objects.requireNonNull(command, "command");
        return new PlayerBusinessResponseQuery(
                command.playerId(),
                command.sessionId(),
                command.sessionEpoch(),
                command.sequence(),
                command.operation()
        );
    }
}
