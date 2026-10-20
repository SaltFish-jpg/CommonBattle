package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerCommandResult;
import com.commonbattle.game.session.PlayerCommandStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 玩家命令在入口层被拒绝时转换成统一业务失败信封。
 */
public final class PlayerCommandDispatchException extends RuntimeException {
    private final PlayerCommandStatus status;
    private final Duration retryAfter;
    private final PlayerBusinessRedirect redirect;

    public PlayerCommandDispatchException(PlayerCommandResult result) {
        super(message(result));
        this.status = Objects.requireNonNull(result, "result").status();
        this.retryAfter = result.retryAfter();
        this.redirect = redirectOf(result);
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

    public Optional<PlayerBusinessRedirect> redirect() {
        return Optional.ofNullable(redirect);
    }

    private static String message(PlayerCommandResult result) {
        Objects.requireNonNull(result, "result");
        return result.reason() == null || result.reason().isBlank()
                ? result.status().name()
                : result.reason();
    }

    private static PlayerBusinessRedirect redirectOf(PlayerCommandResult result) {
        if (result.status() != PlayerCommandStatus.ROUTED_REMOTE || result.route().isEmpty()) {
            return null;
        }
        return result.route().orElseThrow().location()
                .map(location -> new PlayerBusinessRedirect(
                        location.serviceId().wireName(),
                        location.actorRef().id(),
                        "owner_redirect"
                ))
                .orElse(null);
    }
}
