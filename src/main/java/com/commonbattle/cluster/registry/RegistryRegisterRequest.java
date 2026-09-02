package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ServiceDescriptor;

import java.time.Duration;
import java.util.Objects;

/**
 * 服务向中心注册自身描述。
 */
public record RegistryRegisterRequest(ServiceDescriptor service, Duration leaseTtl) {
    public RegistryRegisterRequest(ServiceDescriptor service) {
        this(service, Duration.ZERO);
    }

    public RegistryRegisterRequest {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        if (leaseTtl.isNegative()) {
            throw new IllegalArgumentException("leaseTtl must not be negative");
        }
    }
}
