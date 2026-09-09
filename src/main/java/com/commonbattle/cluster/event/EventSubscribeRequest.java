package com.commonbattle.cluster.event;

import com.commonbattle.cluster.ServiceId;

import java.time.Duration;
import java.util.Set;

/**
 * 服务订阅跨服事件 topic。
 * ownerKeys 为空表示订阅整个 topic；非空时只接收这些 owner 的事件。
 */
public record EventSubscribeRequest(ServiceId subscriber, String topic, Set<String> ownerKeys, Duration leaseTtl) {
    public EventSubscribeRequest() {
        this(ServiceId.of(com.commonbattle.cluster.ServiceKind.CENTER, "default", "default"), "", Set.of(), Duration.ZERO);
    }

    public EventSubscribeRequest(ServiceId subscriber, String topic) {
        this(subscriber, topic, Set.of(), Duration.ZERO);
    }

    public EventSubscribeRequest(ServiceId subscriber, String topic, Set<String> ownerKeys) {
        this(subscriber, topic, ownerKeys, Duration.ZERO);
    }

    public EventSubscribeRequest {
        java.util.Objects.requireNonNull(subscriber, "subscriber");
        java.util.Objects.requireNonNull(topic, "topic");
        java.util.Objects.requireNonNull(ownerKeys, "ownerKeys");
        java.util.Objects.requireNonNull(leaseTtl, "leaseTtl");
        if (leaseTtl.isNegative()) {
            throw new IllegalArgumentException("leaseTtl must not be negative");
        }
        ownerKeys = Set.copyOf(ownerKeys);
    }
}
