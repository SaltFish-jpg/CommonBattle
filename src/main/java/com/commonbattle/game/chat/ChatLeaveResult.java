package com.commonbattle.game.chat;

import java.util.Objects;

/**
 * 离开频道响应。
 */
public record ChatLeaveResult(ChatLeaveStatus status, String channelId, long playerId, int members) {
    public ChatLeaveResult {
        Objects.requireNonNull(status, "status");
        channelId = ChatJoinRequest.normalizeChannelId(channelId);
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (members < 0) {
            throw new IllegalArgumentException("members must not be negative");
        }
    }
}
