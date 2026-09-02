package com.commonbattle.game.session;

import com.commonbattle.actor.ActorContext;

/**
 * 玩家命令处理器。
 * 实现会在玩家 Actor 邮箱中执行，可以安全修改玩家内存状态。
 */
@FunctionalInterface
public interface PlayerCommandHandler {
    void handle(ActorContext context, PlayerCommand command);
}
