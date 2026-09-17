package com.commonbattle.game.session;

import com.commonbattle.game.player.PlayerBusinessResponse;
import com.commonbattle.game.player.PlayerBusinessResponseStatus;

import java.util.Objects;

/**
 * 玩家客户端命令响应。
 * 它是面向客户端的稳定回包外壳，业务模块的领域结果放在 payload 中继续按注册 codec 编码。
 */
public record PlayerClientCommandResponse(
        long playerId,
        String sessionId,
        long sessionEpoch,
        long sequence,
        String operation,
        PlayerBusinessResponseStatus status,
        String code,
        String message,
        long retryAfterMillis,
        boolean replayed,
        Object payload
) {
    public PlayerClientCommandResponse {
        sessionId = Objects.requireNonNull(sessionId, "sessionId").trim();
        operation = Objects.requireNonNull(operation, "operation").trim();
        status = Objects.requireNonNull(status, "status");
        code = code == null || code.isBlank() ? PlayerBusinessResponse.OK : code.trim();
        message = Objects.requireNonNullElse(message, "").trim();
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

    public PlayerClientCommandResponse(
            long playerId,
            String sessionId,
            long sessionEpoch,
            long sequence,
            String operation,
            PlayerBusinessResponseStatus status,
            String code,
            String message,
            boolean replayed,
            Object payload
    ) {
        this(playerId, sessionId, sessionEpoch, sequence, operation, status, code, message, 0, replayed, payload);
    }

    public static PlayerClientCommandResponse from(PlayerBusinessResponse response, boolean replayed) {
        return new PlayerClientCommandResponse(
                response.playerId(),
                response.sessionId(),
                response.sessionEpoch(),
                response.sequence(),
                response.operation(),
                response.status(),
                response.code(),
                response.message(),
                response.retryAfterMillis(),
                replayed,
                response.payload()
        );
    }
}
