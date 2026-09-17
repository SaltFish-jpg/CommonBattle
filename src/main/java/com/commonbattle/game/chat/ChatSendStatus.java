package com.commonbattle.game.chat;

/**
 * 聊天发送结果。
 */
public enum ChatSendStatus {
    SENT,
    NOT_IN_CHANNEL,
    EMPTY_TEXT,
    STALE_PROFILE,
    STALE_FRIENDS,
    STALE_ALLIANCE,
    NOT_FRIEND,
    NOT_ALLIANCE_MEMBER,
    MUTED,
    BLOCKED,
    BACKPRESSURED
}
