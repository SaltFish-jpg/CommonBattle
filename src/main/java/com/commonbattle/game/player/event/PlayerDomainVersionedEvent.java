package com.commonbattle.game.player.event;

import com.commonbattle.game.event.VersionedEvent;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 玩家业务领域事件的跨服版本事件包装。
 * Scene、Chat、联盟等非 owner 服务订阅它感知玩家侧业务变化，具体业务仍在玩家邮箱内先完成本地结算。
 */
public record PlayerDomainVersionedEvent(
        long playerId,
        String eventType,
        String subject,
        int delta,
        long revision,
        Instant occurredAt,
        Map<String, String> attributes
) implements VersionedEvent {
    public static final String TOPIC = "player.domain.event";

    public PlayerDomainVersionedEvent(
            long playerId,
            String eventType,
            String subject,
            int delta,
            long revision,
            Instant occurredAt
    ) {
        this(playerId, eventType, subject, delta, revision, occurredAt, Map.of());
    }

    public PlayerDomainVersionedEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(attributes, "attributes");
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (delta <= 0) {
            throw new IllegalArgumentException("delta must be positive");
        }
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        attributes.forEach((key, value) -> {
            Objects.requireNonNull(key, "attribute key");
            Objects.requireNonNull(value, "attribute value");
            if (key.isBlank()) {
                throw new IllegalArgumentException("attribute key must not be blank");
            }
        });
        attributes = Map.copyOf(attributes);
    }

    public static PlayerDomainVersionedEvent from(
            long playerId,
            long revision,
            Instant occurredAt,
            PlayerDomainEvent event
    ) {
        Objects.requireNonNull(event, "event");
        return new PlayerDomainVersionedEvent(
                playerId,
                event.type(),
                event.subject(),
                event.delta(),
                revision,
                occurredAt,
                event.attributes()
        );
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String ownerKey() {
        return ownerKey(playerId);
    }

    public static String ownerKey(long playerId) {
        return "player:" + playerId;
    }
}
