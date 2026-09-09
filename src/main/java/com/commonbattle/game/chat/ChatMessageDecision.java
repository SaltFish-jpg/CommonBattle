package com.commonbattle.game.chat;

import java.util.Objects;

/**
 * 聊天策略判定结果。
 */
public record ChatMessageDecision(ChatSendStatus status, String displayName, String normalizedText) {
    public ChatMessageDecision {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(normalizedText, "normalizedText");
        if (status == ChatSendStatus.SENT && (displayName.isBlank() || normalizedText.isBlank())) {
            throw new IllegalArgumentException("sent decision requires display name and text");
        }
    }

    public static ChatMessageDecision sent(String displayName, String normalizedText) {
        return new ChatMessageDecision(ChatSendStatus.SENT, displayName, normalizedText);
    }

    public static ChatMessageDecision rejected(ChatSendStatus status) {
        if (status == ChatSendStatus.SENT) {
            throw new IllegalArgumentException("sent status requires delivery data");
        }
        return new ChatMessageDecision(status, "", "");
    }
}
