package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandHandler;

import java.util.Objects;

/**
 * 示例玩家业务命令注册器。
 * 启动层可把同一个通用 handler 绑定到活动、养成、商店等稳定操作名。
 */
public final class PlayerBusinessCommandBinder {
    private PlayerBusinessCommandBinder() {
    }

    public static void registerExamples(PlayerCommandDispatcher dispatcher, PlayerCommandHandler handler) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(handler, "handler");
        PlayerBusinessCommandCatalog.operations().forEach(operation -> dispatcher.handle(operation, handler));
        PlayerBusinessCommandBindingValidator.validateExamples(dispatcher);
    }
}
