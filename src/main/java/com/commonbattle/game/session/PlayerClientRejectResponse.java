package com.commonbattle.game.session;

import java.util.Objects;

/**
 * 玩家客户端通用拒绝响应。
 */
public record PlayerClientRejectResponse(
        PlayerClientErrorCode code,
        String message,
        long retryAfterMillis,
        boolean closeConnection
) {
    public PlayerClientRejectResponse {
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNullElse(message, "").trim();
        retryAfterMillis = Math.max(0, retryAfterMillis);
    }

    public PlayerClientRejectResponse(
            PlayerClientErrorCode code,
            String message,
            boolean closeConnection
    ) {
        this(code, message, 0, closeConnection);
    }
}
