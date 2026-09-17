package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerCommandResult;
import com.commonbattle.game.session.PlayerCommandStatus;

import java.time.Duration;
import java.util.Objects;

/**
 * 玩家命令在入口层被拒绝时转换成统一业务失败信封。
 */
public final class PlayerCommandDispatchException extends RuntimeException {
    private final PlayerCommandStatus status;
    private final Duration retryAfter;

    public PlayerCommandDispatchException(PlayerCommandResult result) {
        super(message(result));
        this.status = Objects.requireNonNull(result, "result").status();
        this.retryAfter = result.retryAfter();
    }

    public PlayerCommandStatus status() {
        return status;
    }

    public String code() {
        return status.name();
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    private static String message(PlayerCommandResult result) {
        Objects.requireNonNull(result, "result");
        return result.reason() == null || result.reason().isBlank()
                ? result.status().name()
                : result.reason();
    }
}
