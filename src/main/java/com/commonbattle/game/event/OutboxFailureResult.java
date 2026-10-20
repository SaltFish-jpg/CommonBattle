package com.commonbattle.game.event;

/**
 * outbox 记录一次发布失败后的状态。
 */
public enum OutboxFailureResult {
    RETRY_SCHEDULED,
    DEAD_LETTERED,
    ENTRY_NOT_FOUND
}
