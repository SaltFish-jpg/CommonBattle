package com.commonbattle.game.session;

import java.util.Optional;

/**
 * 玩家会话注册表。
 * 同一玩家只能绑定一个当前会话，新会话绑定会让旧会话失效。
 */
public interface PlayerSessionRegistry {
    PlayerSession bind(long playerId, String sessionId);

    Optional<PlayerSession> current(long playerId);

    boolean isCurrent(long playerId, String sessionId, long epoch);

    void unbind(PlayerSession session);
}
