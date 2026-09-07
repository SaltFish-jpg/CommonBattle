package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandHandler;

import java.util.List;
import java.util.Objects;

/**
 * 示例玩家业务命令注册器。
 * 启动层可把同一个通用 handler 绑定到活动、养成、商店等稳定操作名。
 */
public final class PlayerBusinessCommandBinder {
    private static final List<String> EXAMPLE_OPERATIONS = List.of(
            PlayerBusinessOperations.ACTIVITY_PROGRESS,
            PlayerBusinessOperations.ACTIVITY_CLAIM,
            PlayerBusinessOperations.GROWTH_USE_EXP_ITEMS,
            PlayerBusinessOperations.SHOP_BUY,
            PlayerBusinessOperations.BATTLE_CLEAR_STAGE,
            PlayerBusinessOperations.BATTLE_SWEEP_STAGE,
            PlayerBusinessOperations.TASK_CLAIM,
            PlayerBusinessOperations.ACHIEVEMENT_CLAIM
    );

    private PlayerBusinessCommandBinder() {
    }

    public static void registerExamples(PlayerCommandDispatcher dispatcher, PlayerCommandHandler handler) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(handler, "handler");
        EXAMPLE_OPERATIONS.forEach(operation -> dispatcher.handle(operation, handler));
    }
}
