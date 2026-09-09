package com.commonbattle.game.chat;

import java.util.Objects;

/**
 * 频道发言请求。
 * requiredProfileRevision 用于事件驱动链路中避免读取旧昵称、旧外观等基础资料快照。
 */
public record ChatSendRequest(String channelId, long senderId, String text, long requiredProfileRevision) {
    public ChatSendRequest {
        channelId = ChatJoinRequest.normalizeChannelId(channelId);
        Objects.requireNonNull(text, "text");
        if (senderId <= 0) {
            throw new IllegalArgumentException("senderId must be positive");
        }
        if (requiredProfileRevision < 0) {
            throw new IllegalArgumentException("requiredProfileRevision must not be negative");
        }
    }
}
