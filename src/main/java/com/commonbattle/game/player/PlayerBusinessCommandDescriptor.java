package com.commonbattle.game.player;

import java.util.Objects;

/**
 * 玩家业务命令元数据。
 * 用于把稳定操作名、业务域和命令类型集中登记，启动绑定、协议覆盖测试和运维展示可以共享同一份清单。
 */
public record PlayerBusinessCommandDescriptor(
        String domain,
        String operation,
        Class<? extends PlayerBusinessCommand<?>> commandType,
        Class<?> responseType
) {
    public PlayerBusinessCommandDescriptor {
        if (Objects.requireNonNull(domain, "domain").isBlank()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
        if (Objects.requireNonNull(operation, "operation").isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        Objects.requireNonNull(commandType, "commandType");
        Objects.requireNonNull(responseType, "responseType");
    }
}
