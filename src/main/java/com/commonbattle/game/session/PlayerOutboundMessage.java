package com.commonbattle.game.session;

import java.time.Instant;
import java.util.Objects;

/**
 * 准备投递给客户端的玩家出站消息。
 * 业务 Actor 只产生消息对象，实际 Netty 写出、离线缓冲和重放由投递层负责。
 */
public record PlayerOutboundMessage(
        long playerId,
        String topic,
        Object payload,
        long sequence,
        Instant createdAt,
        PlayerOutboundDeliveryMode mode,
        String coalesceKey
) {
    public PlayerOutboundMessage(long playerId, String topic, Object payload, long sequence, Instant createdAt) {
        this(playerId, topic, payload, sequence, createdAt, PlayerOutboundDeliveryMode.RELIABLE, "");
    }

    public PlayerOutboundMessage {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        topic = Objects.requireNonNull(topic, "topic").trim();
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
        mode = Objects.requireNonNull(mode, "mode");
        coalesceKey = Objects.requireNonNullElse(coalesceKey, "").trim();
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (mode == PlayerOutboundDeliveryMode.COALESCING && coalesceKey.isBlank()) {
            coalesceKey = topic;
        }
    }
}
