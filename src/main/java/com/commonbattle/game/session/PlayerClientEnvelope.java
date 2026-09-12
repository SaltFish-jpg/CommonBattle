package com.commonbattle.game.session;

import java.time.Instant;
import java.util.Objects;

/**
 * 玩家客户端出站 wire 信封。
 * 它只包含路由到单个玩家连接所需的稳定字段，payload 由注册表决定 protobuf 或 protostuff 编码。
 */
public record PlayerClientEnvelope(
        long playerId,
        String topic,
        Object payload,
        long sequence,
        Instant createdAt
) {
    public PlayerClientEnvelope {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        topic = Objects.requireNonNull(topic, "topic").trim();
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }

    public static PlayerClientEnvelope from(PlayerOutboundMessage message) {
        return new PlayerClientEnvelope(
                message.playerId(),
                message.topic(),
                message.payload(),
                message.sequence(),
                message.createdAt()
        );
    }

    public PlayerOutboundMessage toOutboundMessage() {
        return new PlayerOutboundMessage(playerId, topic, payload, sequence, createdAt);
    }
}
