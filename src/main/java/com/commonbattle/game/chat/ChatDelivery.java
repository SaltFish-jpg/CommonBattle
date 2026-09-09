package com.commonbattle.game.chat;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条已经通过频道 Actor 串行确认的聊天投递。
 */
public record ChatDelivery(
        String channelId,
        long senderId,
        String senderName,
        String text,
        long revision,
        Instant sentAt
) {
    public ChatDelivery {
        channelId = ChatJoinRequest.normalizeChannelId(channelId);
        Objects.requireNonNull(senderName, "senderName");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sentAt, "sentAt");
        if (senderId <= 0) {
            throw new IllegalArgumentException("senderId must be positive");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }
}
