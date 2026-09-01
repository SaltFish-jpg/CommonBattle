package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;

import java.util.Map;
import java.util.Set;

final class ClusterDescriptors {
    private ClusterDescriptors() {
    }

    static ServiceDescriptor fromConfig(ClusterNodeConfig config) {
        return descriptor(config.serviceId(), config.endpoint());
    }

    static ServiceDescriptor center(ClusterNodeConfig config) {
        return descriptor(config.centerServiceId(), config.centerEndpoint());
    }

    static ServiceDescriptor descriptor(ServiceId serviceId, ServiceEndpoint endpoint) {
        return new ServiceDescriptor(serviceId, endpoint, topics(serviceId.kind()), Map.of());
    }

    private static Set<String> topics(ServiceKind kind) {
        return switch (kind) {
            case CENTER -> Set.of("registry.register", "registry.unregister", "registry.list", "registry.subscribe");
            case REGION -> Set.of("region.route", "region.heartbeat");
            case GAME -> Set.of("game.resume", "game.heartbeat");
            case SCENE -> Set.of("scene.enter", "scene.leave", "scene.message");
            case PROXY -> Set.of("proxy.forward", "proxy.heartbeat");
        };
    }
}
