package com.commonbattle.game.agent;

import com.commonbattle.game.player.PlayerBusinessResponse;
import com.commonbattle.game.session.PlayerCommand;

import java.util.Objects;

/**
 * 玩家业务命令请求失败。
 * 用于业务 Agent 通过统一消息端口调用玩家 Actor 时，把失败响应作为请求方 mailbox 内的异常交给调用者处理。
 */
public final class PlayerBusinessCommandRequestException extends RuntimeException {
    private final PlayerCommand command;
    private final PlayerBusinessResponse response;

    public PlayerBusinessCommandRequestException(PlayerCommand command, PlayerBusinessResponse response) {
        super("Player business command failed: playerId=" + Objects.requireNonNull(command, "command").playerId()
                + ", operation=" + command.operation()
                + ", code=" + Objects.requireNonNull(response, "response").code());
        this.command = command;
        this.response = response;
    }

    public PlayerCommand command() {
        return command;
    }

    public PlayerBusinessResponse response() {
        return response;
    }
}
