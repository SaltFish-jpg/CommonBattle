package com.commonbattle.game.agent;

import com.commonbattle.game.session.PlayerCommand;

import java.util.Objects;

/**
 * 玩家业务命令返回 payload 类型与调用方声明不一致。
 * 该异常用于尽早暴露协议绑定错误，避免业务代码在错误类型上继续执行。
 */
public final class PlayerBusinessCommandPayloadTypeException extends RuntimeException {
    private final PlayerCommand command;
    private final Class<?> expectedType;
    private final Class<?> actualType;

    public PlayerBusinessCommandPayloadTypeException(
            PlayerCommand command,
            Class<?> expectedType,
            Class<?> actualType
    ) {
        super("Player business command payload type mismatch: playerId="
                + Objects.requireNonNull(command, "command").playerId()
                + ", operation=" + command.operation()
                + ", expected=" + Objects.requireNonNull(expectedType, "expectedType").getName()
                + ", actual=" + Objects.requireNonNull(actualType, "actualType").getName());
        this.command = command;
        this.expectedType = expectedType;
        this.actualType = actualType;
    }

    public PlayerCommand command() {
        return command;
    }

    public Class<?> expectedType() {
        return expectedType;
    }

    public Class<?> actualType() {
        return actualType;
    }
}
