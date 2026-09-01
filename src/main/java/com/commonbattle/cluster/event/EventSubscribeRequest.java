package com.commonbattle.cluster.event;

import com.commonbattle.cluster.ServiceId;

/**
 * 服务订阅跨服事件 topic。
 */
public record EventSubscribeRequest(ServiceId subscriber, String topic) {
    public EventSubscribeRequest() {
        this(ServiceId.of(com.commonbattle.cluster.ServiceKind.CENTER, "default", "default"), "");
    }
}
