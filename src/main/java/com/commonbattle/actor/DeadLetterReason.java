package com.commonbattle.actor;

/**
 * 消息无法进入 Actor 邮箱的原因。
 */
public enum DeadLetterReason {
    MAILBOX_FULL,
    SYSTEM_CLOSED
}
