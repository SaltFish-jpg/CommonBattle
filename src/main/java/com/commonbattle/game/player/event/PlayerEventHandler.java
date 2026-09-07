package com.commonbattle.game.player.event;

import com.commonbattle.game.player.PlayerGameExecution;

/**
 * 玩家业务事件处理器。
 * 实现类应假设自己运行在玩家 Actor 邮箱内，可以安全读写该玩家聚合状态。
 */
public interface PlayerEventHandler {
    void handle(PlayerGameExecution execution, PlayerDomainEvent event);
}
