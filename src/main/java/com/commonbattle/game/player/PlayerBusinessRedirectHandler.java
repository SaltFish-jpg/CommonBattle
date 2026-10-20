package com.commonbattle.game.player;

import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.game.session.PlayerCommand;

/**
 * 玩家业务命令跨服重定向处理器。
 * 调用方收到旧 owner 返回的新 owner 地址后，可在重试前刷新本地路由缓存。
 */
@FunctionalInterface
public interface PlayerBusinessRedirectHandler {
    PlayerBusinessRedirectHandler NOOP = (command, previousOwner, redirect) -> false;

    boolean redirected(PlayerCommand command, AgentLocation previousOwner, PlayerBusinessRedirect redirect);
}
