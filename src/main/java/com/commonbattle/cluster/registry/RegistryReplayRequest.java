package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;

/**
 * 订阅端按本地已知版本向中心请求注册目录增量。
 */
public record RegistryReplayRequest(ServiceId subscriber, ServiceKind kind, long sinceVersion) {
    public RegistryReplayRequest {
        java.util.Objects.requireNonNull(subscriber, "subscriber");
        java.util.Objects.requireNonNull(kind, "kind");
        if (sinceVersion < 0) {
            throw new IllegalArgumentException("sinceVersion must not be negative");
        }
    }
}
