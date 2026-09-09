package com.commonbattle.game.chat;

/**
 * 离开频道请求。
 */
public record ChatLeaveRequest(String channelId, long playerId) {
    public ChatLeaveRequest {
        channelId = ChatJoinRequest.normalizeChannelId(channelId);
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
    }
}
