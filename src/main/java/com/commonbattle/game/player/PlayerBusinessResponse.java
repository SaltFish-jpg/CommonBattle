package com.commonbattle.game.player;

import com.commonbattle.cluster.rpc.RpcCircuitOpenException;
import com.commonbattle.cluster.rpc.RpcNoRoutableServiceException;
import com.commonbattle.cluster.rpc.RpcRejectedException;
import com.commonbattle.cluster.rpc.RpcServiceDrainingException;
import com.commonbattle.cluster.rpc.RpcStructuredException;
import com.commonbattle.cluster.rpc.RpcTimeoutException;
import com.commonbattle.game.GameBusinessFailure;
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
        long retryAfterMillis,
        Object payload
) {
    public static final String OK = "OK";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String BUSINESS_REJECTED = "BUSINESS_REJECTED";
    public static final String SYSTEM_ERROR = "SYSTEM_ERROR";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String REMOTE_TIMEOUT = "REMOTE_TIMEOUT";
    public static final String REMOTE_UNAVAILABLE = "REMOTE_UNAVAILABLE";
    public static final String REMOTE_REJECTED = "REMOTE_REJECTED";
    public static final String REMOTE_DRAINING = "REMOTE_DRAINING";
    public static final String REMOTE_CIRCUIT_OPEN = "REMOTE_CIRCUIT_OPEN";

    public PlayerBusinessResponse {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(status, "status");
        code = code == null || code.isBlank() ? OK : code;
        message = message == null ? "" : message;
        retryAfterMillis = Math.max(0, retryAfterMillis);
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

    public PlayerBusinessResponse(
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
        this(playerId, sessionId, sessionEpoch, sequence, operation, status, code, message, 0, payload);
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
                0,
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
                retryAfterMillis(error),
                null
        );
    }

    private static String failureCode(Throwable error) {
        if (error instanceof PlayerCommandDispatchException dispatch) {
            return dispatch.code();
        }
        if (error instanceof PlayerBusinessResponseTimeoutException) {
            return TIMEOUT;
        }
        if (error instanceof RpcTimeoutException) {
            return REMOTE_TIMEOUT;
        }
        if (error instanceof RpcNoRoutableServiceException) {
            return REMOTE_UNAVAILABLE;
        }
        if (error instanceof RpcRejectedException) {
            return REMOTE_REJECTED;
        }
        if (error instanceof RpcServiceDrainingException) {
            return REMOTE_DRAINING;
        }
        if (error instanceof RpcCircuitOpenException) {
            return REMOTE_CIRCUIT_OPEN;
        }
        if (error instanceof RpcStructuredException structured) {
            return structured.code();
        }
        if (error instanceof GameBusinessFailure failure) {
            return failure.code();
        }
        if (error instanceof IllegalArgumentException) {
            return BAD_REQUEST;
        }
        if (error instanceof IllegalStateException) {
            return BUSINESS_REJECTED;
        }
        return SYSTEM_ERROR;
    }

    private static long retryAfterMillis(Throwable error) {
        if (error instanceof PlayerCommandDispatchException dispatch) {
            return dispatch.retryAfter().toMillis();
        }
        if (error instanceof RpcTimeoutException timeout) {
            return timeout.timeout().toMillis();
        }
        if (error instanceof RpcStructuredException structured) {
            return structured.retryAfter().toMillis();
        }
        if (error instanceof GameBusinessFailure failure) {
            return failure.retryAfterMillis();
        }
        return 0;
    }
}
