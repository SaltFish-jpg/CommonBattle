package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ServiceDescriptor;

import java.util.List;

/**
 * 中心返回的服务列表快照。
 */
public record RegistryListResponse(List<ServiceDescriptor> services, long version) {
    public RegistryListResponse(List<ServiceDescriptor> services) {
        this(services, 0);
    }

    public RegistryListResponse {
        services = List.copyOf(services);
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }
}
