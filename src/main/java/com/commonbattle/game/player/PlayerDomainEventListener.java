package com.commonbattle.game.player;

import com.commonbattle.game.player.event.PlayerDomainEvent;

/**
 * 玩家领域事件监听器。
 * 它在玩家邮箱内、业务状态已经修改后调用，适合做资料投影或轻量本地派生，不应执行阻塞远程读写。
 */
@FunctionalInterface
public interface PlayerDomainEventListener {
    void onEvent(PlayerProfile profile, long eventRevision, PlayerDomainEvent event);
}
