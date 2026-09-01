package com.commonbattle.cluster.event;

import com.commonbattle.cluster.ServiceId;

/**
 * 服务取消订阅跨服事件 topic。
 */
public record EventUnsubscribeRequest(ServiceId subscriber, String topic) {
    public EventUnsubscribeRequest() {
        this(ServiceId.of(com.commonbattle.cluster.ServiceKind.CENTER, "default", "default"), "");
    }
}
