package com.commonbattle.game.chat;

import java.util.Objects;

/**
 * 世界聊天发言请求。
 */
public record WorldChatSendRequest(String worldId, long senderId, String text, long requiredProfileRevision) {
    public WorldChatSendRequest {
        worldId = WorldChatJoinRequest.normalizeWorldId(worldId);
        Objects.requireNonNull(text, "text");
        if (senderId <= 0) {
            throw new IllegalArgumentException("senderId must be positive");
        }
        if (requiredProfileRevision < 0) {
            throw new IllegalArgumentException("requiredProfileRevision must not be negative");
        }
    }
}
