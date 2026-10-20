package com.commonbattle.game.event;

import java.util.Objects;

/**
 * 消费端事件幂等键。
 * 同一 topic、owner 和 revision 在 inbox 中只允许存在一份，重试不会创建第二条业务消息。
 */
public record EventInboxKey(String topic, String ownerKey, long revision) {
    public EventInboxKey {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(ownerKey, "ownerKey");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (ownerKey.isBlank()) {
            throw new IllegalArgumentException("ownerKey must not be blank");
        }
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }

    public static EventInboxKey of(VersionedEvent event) {
        Objects.requireNonNull(event, "event");
        return new EventInboxKey(event.topic(), event.ownerKey(), event.revision());
    }
}
