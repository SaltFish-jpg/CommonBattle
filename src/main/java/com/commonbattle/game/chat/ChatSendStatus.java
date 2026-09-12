package com.commonbattle.game.chat;

/**
 * 聊天发送结果。
 */
public enum ChatSendStatus {
    SENT,
    NOT_IN_CHANNEL,
    EMPTY_TEXT,
    STALE_PROFILE,
    MUTED,
    BLOCKED
}
