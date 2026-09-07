package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerCommand;

import java.util.Objects;

/**
 * 玩家业务命令统一返回信封。
 * 业务模块仍可返回自己的领域结果，本信封负责补齐网关回包、审计和观测需要的公共字段。
 */
public record PlayerBusinessResponse(
        long playerId,
        String sessionId,
        long sessionEpoch,
        long sequence,
        String operation,
        PlayerBusinessResponseStatus status,
        String code,
        String message,
        Object payload
) {
    public static final String OK = "OK";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String BUSINESS_REJECTED = "BUSINESS_REJECTED";
    public static final String SYSTEM_ERROR = "SYSTEM_ERROR";

    public PlayerBusinessResponse {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(status, "status");
        code = code == null || code.isBlank() ? OK : code;
        message = message == null ? "" : message;
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

    public boolean succeeded() {
        return status == PlayerBusinessResponseStatus.SUCCESS;
    }

    public static PlayerBusinessResponse success(PlayerCommand command, Object payload) {
        return new PlayerBusinessResponse(
                command.playerId(),
                command.sessionId(),
                command.sessionEpoch(),
                command.sequence(),
                command.operation(),
                PlayerBusinessResponseStatus.SUCCESS,
                OK,
                "",
                payload
        );
    }

    public static PlayerBusinessResponse failure(PlayerCommand command, Throwable error) {
        return new PlayerBusinessResponse(
                command.playerId(),
                command.sessionId(),
                command.sessionEpoch(),
                command.sequence(),
                command.operation(),
                PlayerBusinessResponseStatus.FAILED,
                failureCode(error),
                error == null || error.getMessage() == null ? "" : error.getMessage(),
                null
        );
    }

    private static String failureCode(Throwable error) {
        if (error instanceof PlayerCommandDispatchException dispatch) {
            return dispatch.code();
        }
        if (error instanceof IllegalArgumentException) {
            return BAD_REQUEST;
        }
        if (error instanceof IllegalStateException) {
            return BUSINESS_REJECTED;
        }
        return SYSTEM_ERROR;
    }
}
