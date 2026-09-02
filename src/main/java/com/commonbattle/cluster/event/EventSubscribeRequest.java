package com.commonbattle.cluster.event;

import com.commonbattle.cluster.ServiceId;

import java.util.Set;

/**
 * 服务订阅跨服事件 topic。
 * ownerKeys 为空表示订阅整个 topic；非空时只接收这些 owner 的事件。
 */
public record EventSubscribeRequest(ServiceId subscriber, String topic, Set<String> ownerKeys) {
    public EventSubscribeRequest() {
        this(ServiceId.of(com.commonbattle.cluster.ServiceKind.CENTER, "default", "default"), "", Set.of());
    }

    public EventSubscribeRequest(ServiceId subscriber, String topic) {
        this(subscriber, topic, Set.of());
    }

    public EventSubscribeRequest {
        ownerKeys = Set.copyOf(ownerKeys);
    }
}
