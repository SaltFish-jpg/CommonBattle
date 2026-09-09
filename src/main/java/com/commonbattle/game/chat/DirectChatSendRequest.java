package com.commonbattle.game.chat;

import java.util.Objects;

/**
 * 私聊发言请求。
 * 一对玩家映射到独立会话 Actor，避免把私聊状态塞进任意玩家 Actor。
 */
public record DirectChatSendRequest(long senderId, long receiverId, String text, long requiredProfileRevision) {
    public DirectChatSendRequest {
        Objects.requireNonNull(text, "text");
        if (senderId <= 0) {
            throw new IllegalArgumentException("senderId must be positive");
        }
        if (receiverId <= 0) {
            throw new IllegalArgumentException("receiverId must be positive");
        }
        if (senderId == receiverId) {
            throw new IllegalArgumentException("receiverId must be different from senderId");
        }
        if (requiredProfileRevision < 0) {
            throw new IllegalArgumentException("requiredProfileRevision must not be negative");
        }
    }
}
