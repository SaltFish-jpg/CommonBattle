package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ServiceDescriptor;

import java.util.List;

/**
 * 中心返回的服务列表快照。
 */
public record RegistryListResponse(List<ServiceDescriptor> services) {
    public RegistryListResponse {
        services = List.copyOf(services);
    }
}
