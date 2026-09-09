package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerCommand;

import java.time.Duration;
import java.util.Objects;

/**
 * 玩家业务命令已经进入邮箱，但在约定时间内没有产生响应。
 */
public final class PlayerBusinessResponseTimeoutException extends RuntimeException {
    private final Duration timeout;

    public PlayerBusinessResponseTimeoutException(PlayerCommand command, Duration timeout) {
        super("player business response timed out after " + timeout.toMillis()
                + " ms: player=" + Objects.requireNonNull(command, "command").playerId()
                + ", sequence=" + command.sequence()
                + ", operation=" + command.operation());
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public Duration timeout() {
        return timeout;
    }
}
