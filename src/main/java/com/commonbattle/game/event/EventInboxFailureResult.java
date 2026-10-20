package com.commonbattle.game.event;

/**
 * inbox 记录一次 Actor 投递或处理失败后的状态。
 */
public enum EventInboxFailureResult {
    RETRY_SCHEDULED,
    DEAD_LETTERED,
    ENTRY_NOT_FOUND
}
