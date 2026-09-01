package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.netty.NettyClusterTransport;

import java.util.List;
import java.util.Objects;

final class DirectoryEndpointView implements NettyClusterTransport.ServiceRegistryView {
    private final ClusterDirectory directory;
    private final ServiceDescriptor local;
    private final ServiceDescriptor center;

    DirectoryEndpointView(ClusterDirectory directory, ServiceDescriptor local, ServiceDescriptor center) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.local = Objects.requireNonNull(local, "local");
        this.center = Objects.requireNonNull(center, "center");
    }

    @Override
    public ServiceEndpoint endpointOf(ServiceId serviceId) {
        if (local.id().equals(serviceId)) {
            return local.endpoint();
        }
        if (center.id().equals(serviceId)) {
            return center.endpoint();
        }
        return List.of(ServiceKind.values()).stream()
                .flatMap(kind -> directory.list(kind).stream())
                .filter(service -> service.id().equals(serviceId))
                .findFirst()
                .map(ServiceDescriptor::endpoint)
                .orElseThrow(() -> new IllegalStateException("No endpoint for " + serviceId.wireName()));
    }
}
