package com.commonbattle.game.session;

import java.util.Objects;

/**
 * 玩家客户端通用拒绝响应。
 */
public record PlayerClientRejectResponse(
        PlayerClientErrorCode code,
        String message,
        boolean closeConnection
) {
    public PlayerClientRejectResponse {
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNullElse(message, "").trim();
    }
}
