package com.commonbattle.game.session;

import java.util.Objects;
import java.util.Set;

/**
 * 一次玩家出站投递请求。
 */
public record PlayerOutboundEnvelope(
        Set<Long> recipients,
        String topic,
        Object payload,
        PlayerOutboundDeliveryMode mode,
        String coalesceKey
) {
    public PlayerOutboundEnvelope(Set<Long> recipients, String topic, Object payload) {
        this(recipients, topic, payload, PlayerOutboundDeliveryMode.RELIABLE, "");
    }

    public PlayerOutboundEnvelope {
        recipients = Set.copyOf(Objects.requireNonNull(recipients, "recipients"));
        topic = Objects.requireNonNull(topic, "topic").trim();
        Objects.requireNonNull(payload, "payload");
        mode = Objects.requireNonNull(mode, "mode");
        coalesceKey = Objects.requireNonNullElse(coalesceKey, "").trim();
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (mode == PlayerOutboundDeliveryMode.COALESCING && coalesceKey.isBlank()) {
            coalesceKey = topic;
        }
    }

    public static PlayerOutboundEnvelope bestEffort(Set<Long> recipients, String topic, Object payload) {
        return new PlayerOutboundEnvelope(recipients, topic, payload, PlayerOutboundDeliveryMode.BEST_EFFORT, "");
    }

    public static PlayerOutboundEnvelope coalescing(Set<Long> recipients, String topic, Object payload, String key) {
        return new PlayerOutboundEnvelope(recipients, topic, payload, PlayerOutboundDeliveryMode.COALESCING, key);
    }
}
