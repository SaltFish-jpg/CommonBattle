package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;

/**
 * 服务向中心取消某类服务变化订阅。
 */
public record RegistryUnsubscribeRequest(ServiceId subscriber, ServiceKind kind) {
    public RegistryUnsubscribeRequest {
        java.util.Objects.requireNonNull(subscriber, "subscriber");
        java.util.Objects.requireNonNull(kind, "kind");
    }
}
