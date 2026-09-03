package com.commonbattle.cluster.boot;

import com.commonbattle.actor.agent.migration.AgentMigrationOperations;
import com.commonbattle.actor.agent.remote.AgentDirectoryOperations;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.event.ClusterEventOperations;
import com.commonbattle.cluster.registry.RegistryOperations;
import com.commonbattle.game.config.GameConfigOperations;
import com.commonbattle.game.profile.ProfileSnapshotOperations;
import com.commonbattle.example.cross.SceneOperations;

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
            case CENTER -> Set.of(
                    RegistryOperations.REGISTER,
                    RegistryOperations.UNREGISTER,
                    RegistryOperations.LIST,
                    RegistryOperations.SUBSCRIBE,
                    AgentDirectoryOperations.CLAIM,
                    AgentDirectoryOperations.MOVE,
                    AgentDirectoryOperations.UNBIND,
                    AgentDirectoryOperations.LOCATE,
                    ClusterEventOperations.SUBSCRIBE,
                    ClusterEventOperations.UNSUBSCRIBE,
                    ClusterEventOperations.PUBLISH,
                    GameConfigOperations.SNAPSHOT
            );
            case REGION -> Set.of("region.route", "region.heartbeat");
            case GAME -> Set.of(
                    "game.resume",
                    "game.heartbeat",
                    ProfileSnapshotOperations.GET,
                    AgentMigrationOperations.ACCEPT
            );
            case SCENE -> Set.of(SceneOperations.ENTER, SceneOperations.LEAVE, SceneOperations.MESSAGE);
            case PROXY -> Set.of("proxy.forward", "proxy.heartbeat");
        };
    }
}
