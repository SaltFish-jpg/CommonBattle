package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;

import java.time.Duration;

/**
 * 服务向中心订阅某类服务变化。
 */
public record RegistrySubscribeRequest(ServiceId subscriber, ServiceKind kind, long sinceVersion, Duration leaseTtl) {
    public RegistrySubscribeRequest(ServiceId subscriber, ServiceKind kind) {
        this(subscriber, kind, 0, Duration.ZERO);
    }

    public RegistrySubscribeRequest(ServiceId subscriber, ServiceKind kind, long sinceVersion) {
        this(subscriber, kind, sinceVersion, Duration.ZERO);
    }

    public RegistrySubscribeRequest {
        java.util.Objects.requireNonNull(subscriber, "subscriber");
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(leaseTtl, "leaseTtl");
        if (sinceVersion < 0) {
            throw new IllegalArgumentException("sinceVersion must not be negative");
        }
        if (leaseTtl.isNegative()) {
            throw new IllegalArgumentException("leaseTtl must not be negative");
        }
    }
}
