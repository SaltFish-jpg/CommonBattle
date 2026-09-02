package com.commonbattle.game.session;

import com.commonbattle.actor.agent.AgentRoute;

import java.util.Optional;

/**
 * 玩家命令入口返回给网关层的准入结果。
 * ACCEPTED 表示已进入本地玩家邮箱；ROUTED_REMOTE 表示应交给跨服 RPC 继续转发。
 */
public record PlayerCommandResult(PlayerCommandStatus status, String reason, Optional<AgentRoute> route) {
    public static PlayerCommandResult accepted() {
        return new PlayerCommandResult(PlayerCommandStatus.ACCEPTED, "", Optional.empty());
    }

    public static PlayerCommandResult duplicate() {
        return new PlayerCommandResult(PlayerCommandStatus.DUPLICATE, "duplicate", Optional.empty());
    }

    public static PlayerCommandResult reject(PlayerCommandStatus status, String reason) {
        return new PlayerCommandResult(status, reason, Optional.empty());
    }

    public static PlayerCommandResult routedRemote(AgentRoute route) {
        return new PlayerCommandResult(PlayerCommandStatus.ROUTED_REMOTE, "", Optional.of(route));
    }
}
