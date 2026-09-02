package com.commonbattle.game.session;

/**
 * 玩家命令入口处理结果。
 */
public enum PlayerCommandStatus {
    ACCEPTED,
    DUPLICATE,
    GAP,
    STALE_SESSION,
    RATE_LIMITED,
    AGENT_MISSING,
    ROUTED_REMOTE,
    UNKNOWN_OPERATION
}
