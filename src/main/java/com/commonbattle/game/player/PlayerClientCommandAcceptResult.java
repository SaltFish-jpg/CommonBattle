package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerClientErrorCode;

import java.time.Duration;
import java.util.Objects;

/**
 * 玩家客户端命令入站接收结果。
 * Netty 线程只根据该结果决定是否回拒连接，业务成功或失败仍由玩家 Actor 响应通道返回。
 */
public record PlayerClientCommandAcceptResult(
        boolean accepted,
        PlayerClientErrorCode code,
        String message,
        Duration retryAfter,
        boolean closeConnection
) {
    public PlayerClientCommandAcceptResult {
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNullElse(message, "").trim();
        retryAfter = Objects.requireNonNullElse(retryAfter, Duration.ZERO);
        if (retryAfter.isNegative()) {
            retryAfter = Duration.ZERO;
        }
        if (accepted && code != PlayerClientErrorCode.OK) {
            throw new IllegalArgumentException("accepted result must use OK code");
        }
        if (!accepted && code == PlayerClientErrorCode.OK) {
            throw new IllegalArgumentException("rejected result must not use OK code");
        }
        if (accepted && closeConnection) {
            closeConnection = false;
        }
    }

    public PlayerClientCommandAcceptResult(
            boolean accepted,
            PlayerClientErrorCode code,
            String message,
            Duration retryAfter
    ) {
        this(accepted, code, message, retryAfter, !accepted);
    }

    public static PlayerClientCommandAcceptResult acceptedResult() {
        return new PlayerClientCommandAcceptResult(true, PlayerClientErrorCode.OK, "", Duration.ZERO, false);
    }

    public static PlayerClientCommandAcceptResult rejected(PlayerClientErrorCode code, String message) {
        return rejected(code, message, Duration.ZERO);
    }

    public static PlayerClientCommandAcceptResult rejected(
            PlayerClientErrorCode code,
            String message,
            Duration retryAfter
    ) {
        return new PlayerClientCommandAcceptResult(false, code, message, retryAfter, true);
    }

    public static PlayerClientCommandAcceptResult rejectedWithoutClosing(
            PlayerClientErrorCode code,
            String message,
            Duration retryAfter
    ) {
        return new PlayerClientCommandAcceptResult(false, code, message, retryAfter, false);
    }
}
