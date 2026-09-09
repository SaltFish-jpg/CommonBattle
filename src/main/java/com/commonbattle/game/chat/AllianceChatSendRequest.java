package com.commonbattle.game.chat;

import java.util.Objects;

/**
 * 联盟聊天发言请求。
 */
public record AllianceChatSendRequest(long allianceId, long senderId, String text, long requiredProfileRevision) {
    public AllianceChatSendRequest {
        Objects.requireNonNull(text, "text");
        if (allianceId <= 0) {
            throw new IllegalArgumentException("allianceId must be positive");
        }
        if (senderId <= 0) {
            throw new IllegalArgumentException("senderId must be positive");
        }
        if (requiredProfileRevision < 0) {
            throw new IllegalArgumentException("requiredProfileRevision must not be negative");
        }
    }
}
