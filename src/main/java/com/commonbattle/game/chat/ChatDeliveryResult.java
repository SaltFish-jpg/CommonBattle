package com.commonbattle.game.chat;

/**
 * Chat 投递结果。
 */
public record ChatDeliveryResult(long acceptedRecipients, long droppedRecipients, long failedRecipients) {
    public ChatDeliveryResult {
        if (acceptedRecipients < 0 || droppedRecipients < 0 || failedRecipients < 0) {
            throw new IllegalArgumentException("delivery counters must not be negative");
        }
    }

    public static ChatDeliveryResult empty() {
        return new ChatDeliveryResult(0, 0, 0);
    }

    public ChatDeliveryResult plus(ChatDeliveryResult other) {
        return new ChatDeliveryResult(
                acceptedRecipients + other.acceptedRecipients,
                droppedRecipients + other.droppedRecipients,
                failedRecipients + other.failedRecipients
        );
    }
}
