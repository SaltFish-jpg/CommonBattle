package com.commonbattle.game.player;

/**
 * 玩家邮箱内执行的业务命令。
 * 网络、RPC 或定时器入口可把业务参数封装成该命令，再交给统一命令处理器进入玩家 Actor。
 */
public interface PlayerBusinessCommand<R> {
    String operation();

    R execute(PlayerGameExecution execution);
}
