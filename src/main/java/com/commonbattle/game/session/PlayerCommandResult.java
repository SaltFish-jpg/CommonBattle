package com.commonbattle.game.session;

import com.commonbattle.actor.agent.AgentRoute;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 玩家命令入口返回给网关层的准入结果。
 * ACCEPTED 表示已进入本地玩家邮箱；ROUTED_REMOTE 表示应交给跨服 RPC 继续转发。
 */
public record PlayerCommandResult(
        PlayerCommandStatus status,
        String reason,
        Optional<AgentRoute> route,
        Optional<PlayerCommandStatus> originalStatus,
        Duration retryAfter
) {
    public PlayerCommandResult(
            PlayerCommandStatus status,
            String reason,
            Optional<AgentRoute> route,
            Optional<PlayerCommandStatus> originalStatus
    ) {
        this(status, reason, route, originalStatus, Duration.ZERO);
    }

    public PlayerCommandResult {
        retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
    }

    public static PlayerCommandResult accepted() {
        return new PlayerCommandResult(PlayerCommandStatus.ACCEPTED, "", Optional.empty(), Optional.empty(), Duration.ZERO);
    }

    public static PlayerCommandResult duplicate() {
        return duplicateOf(PlayerCommandStatus.ACCEPTED);
    }

    public static PlayerCommandResult duplicateOf(PlayerCommandStatus originalStatus) {
        PlayerCommandStatus status = Objects.requireNonNull(originalStatus, "originalStatus");
        return new PlayerCommandResult(PlayerCommandStatus.DUPLICATE, "duplicate:" + status.name(),
                Optional.empty(), Optional.of(status), Duration.ZERO);
    }

    public static PlayerCommandResult reject(PlayerCommandStatus status, String reason) {
        return reject(status, reason, Duration.ZERO);
    }

    public static PlayerCommandResult reject(PlayerCommandStatus status, String reason, Duration retryAfter) {
        return new PlayerCommandResult(status, reason, Optional.empty(), Optional.empty(), retryAfter);
    }

    public static PlayerCommandResult routedRemote(AgentRoute route) {
        return new PlayerCommandResult(PlayerCommandStatus.ROUTED_REMOTE, "", Optional.of(route), Optional.empty(), Duration.ZERO);
    }

    public boolean waitForExistingResponse() {
        return status == PlayerCommandStatus.DUPLICATE
                && originalStatus.orElse(PlayerCommandStatus.ACCEPTED) == PlayerCommandStatus.ACCEPTED;
    }
}
