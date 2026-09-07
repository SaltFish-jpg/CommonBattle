package com.commonbattle.game.session;

/**
 * Game 服已进入摘流状态时拒绝新登录。
 */
public final class PlayerLoginDrainingException extends RuntimeException {
    public PlayerLoginDrainingException(long playerId) {
        super("player login rejected while draining: " + playerId);
    }
}
