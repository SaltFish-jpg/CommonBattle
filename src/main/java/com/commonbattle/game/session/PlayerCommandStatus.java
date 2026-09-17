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
    BACKPRESSURED,
    DRAINING,
    AGENT_MIGRATING,
    AGENT_MISSING,
    MAILBOX_FULL,
    ROUTED_REMOTE,
    UNKNOWN_OPERATION
}
