package com.commonbattle.cluster.event;

import com.commonbattle.game.event.VersionedEvent;

import java.util.Objects;

/**
 * 中心日志事件的业务幂等键。
 */
public record ClusterEventJournalKey(String topic, String ownerKey, long revision) {
    public ClusterEventJournalKey {
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

    public static ClusterEventJournalKey of(VersionedEvent event) {
        Objects.requireNonNull(event, "event");
        return new ClusterEventJournalKey(event.topic(), event.ownerKey(), event.revision());
    }
}
