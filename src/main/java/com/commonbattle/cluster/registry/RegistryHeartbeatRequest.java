package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ServiceId;

import java.time.Duration;
import java.util.Objects;

/**
 * 服务向中心续约自己的注册租约。
 */
public record RegistryHeartbeatRequest(ServiceId serviceId, Duration leaseTtl) {
    public RegistryHeartbeatRequest {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        if (leaseTtl.isZero() || leaseTtl.isNegative()) {
            throw new IllegalArgumentException("leaseTtl must be positive");
        }
    }
}
