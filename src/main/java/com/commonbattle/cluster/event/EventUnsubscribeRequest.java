package com.commonbattle.cluster.event;

import com.commonbattle.cluster.ServiceId;

import java.util.Set;

/**
 * 服务取消订阅跨服事件 topic。
 * ownerKeys 为空表示取消该 topic 下的全部订阅；非空时只取消指定 owner 订阅。
 */
public record EventUnsubscribeRequest(ServiceId subscriber, String topic, Set<String> ownerKeys) {
    public EventUnsubscribeRequest() {
        this(ServiceId.of(com.commonbattle.cluster.ServiceKind.CENTER, "default", "default"), "", Set.of());
    }

    public EventUnsubscribeRequest(ServiceId subscriber, String topic) {
        this(subscriber, topic, Set.of());
    }

    public EventUnsubscribeRequest {
        ownerKeys = Set.copyOf(ownerKeys);
    }
}
