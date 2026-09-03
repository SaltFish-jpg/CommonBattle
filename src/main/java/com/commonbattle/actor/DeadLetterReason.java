package com.commonbattle.actor;

/**
 * 消息无法进入 Actor 邮箱的原因。
 */
public enum DeadLetterReason {
    MAILBOX_FULL,
    MAILBOX_CATEGORY_FULL,
    DROPPED_BY_OVERFLOW,
    SYSTEM_CLOSED
}
