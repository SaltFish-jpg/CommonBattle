package com.commonbattle.game.session;

/**
 * 玩家命令审计结果。
 */
public enum PlayerCommandAuditOutcome {
    EXECUTED,
    FAILED,
    REJECTED,
    ROUTED_REMOTE
}
