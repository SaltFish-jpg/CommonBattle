package com.commonbattle.game.chat;

import java.util.Objects;
import java.util.Optional;

/**
 * 频道发言响应。
 */
public record ChatSendResult(ChatSendStatus status, Optional<ChatDelivery> delivery) {
    public ChatSendResult {
        Objects.requireNonNull(status, "status");
        delivery = Objects.requireNonNull(delivery, "delivery");
        if (status == ChatSendStatus.SENT && delivery.isEmpty()) {
            throw new IllegalArgumentException("sent result must include delivery");
        }
    }

    public static ChatSendResult sent(ChatDelivery delivery) {
        return new ChatSendResult(ChatSendStatus.SENT, Optional.of(delivery));
    }

    public static ChatSendResult rejected(ChatSendStatus status) {
        if (status == ChatSendStatus.SENT) {
            throw new IllegalArgumentException("sent status requires delivery");
        }
        return new ChatSendResult(status, Optional.empty());
    }
}
