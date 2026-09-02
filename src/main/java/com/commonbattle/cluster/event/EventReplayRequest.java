package com.commonbattle.cluster.event;

import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 订阅服务按 owner/revision 游标向事件中心请求历史事件重放。
 * ownerKeys 为空表示重放整个 topic；非空时只重放指定 owner 的历史事件。
 */
public record EventReplayRequest(
        ServiceId subscriber,
        String topic,
        Map<String, Long> knownRevisions,
        Set<String> ownerKeys
) {
    public EventReplayRequest() {
        this(ServiceId.of(ServiceKind.CENTER, "default", "default"), "default", Map.of(), Set.of());
    }

    public EventReplayRequest(ServiceId subscriber, String topic, Map<String, Long> knownRevisions) {
        this(subscriber, topic, knownRevisions, Set.of());
    }

    public EventReplayRequest {
        Objects.requireNonNull(subscriber, "subscriber");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(knownRevisions, "knownRevisions");
        Objects.requireNonNull(ownerKeys, "ownerKeys");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("event topic must not be blank");
        }
        knownRevisions.forEach((ownerKey, revision) -> {
            if (ownerKey == null || ownerKey.isBlank()) {
                throw new IllegalArgumentException("ownerKey must not be blank");
            }
            if (revision == null || revision < 0) {
                throw new IllegalArgumentException("known revision must not be negative");
            }
        });
        knownRevisions = Map.copyOf(knownRevisions);
        ownerKeys.forEach(ownerKey -> {
            if (ownerKey == null || ownerKey.isBlank()) {
                throw new IllegalArgumentException("ownerKey must not be blank");
            }
        });
        ownerKeys = Set.copyOf(ownerKeys);
    }
}
