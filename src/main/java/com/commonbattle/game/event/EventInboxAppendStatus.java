package com.commonbattle.game.event;

/**
 * 事件写入消费端 inbox 的结果。
 */
public enum EventInboxAppendStatus {
    APPENDED,
    ALREADY_PENDING,
    DEAD_LETTERED
}
