package com.commonbattle.game.chat;

/**
 * 玩家加入聊天频道的结果。
 */
public enum ChatJoinStatus {
    JOINED,
    ALREADY_JOINED,
    STALE_ALLIANCE,
    NOT_ALLIANCE_MEMBER,
    BACKPRESSURED
}
