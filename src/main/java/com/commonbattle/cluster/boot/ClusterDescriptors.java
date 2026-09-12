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
import com.commonbattle.game.agent.BusinessAgentRpcOperations;
import com.commonbattle.game.chat.ChatOperations;
import com.commonbattle.game.player.PlayerBusinessRpcOperations;
import com.commonbattle.game.profile.ProfileSnapshotOperations;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.game.shop.ShopStockOperations;
import com.commonbattle.game.social.AllianceSnapshotOperations;
import com.commonbattle.game.social.FriendSnapshotOperations;

import java.util.Map;
import java.util.Set;

final class ClusterDescriptors {
    private ClusterDescriptors() {
    }

    static ServiceDescriptor fromConfig(ClusterNodeConfig config) {
        return withConfigMetadata(descriptor(config.serviceId(), config.endpoint()), config);
    }

    static ServiceDescriptor center(ClusterNodeConfig config) {
        return descriptor(config.centerServiceId(), config.centerEndpoint());
    }

    static ServiceDescriptor descriptor(ServiceId serviceId, ServiceEndpoint endpoint) {
        return new ServiceDescriptor(serviceId, endpoint, topics(serviceId.kind()), Map.of());
    }

    static ServiceDescriptor withConfigMetadata(ServiceDescriptor descriptor, ClusterNodeConfig config) {
        if (config.serviceMetadata().isEmpty()) {
            return descriptor;
        }
        java.util.Map<String, String> metadata = new java.util.HashMap<>(descriptor.metadata());
        metadata.putAll(config.serviceMetadata());
        return descriptor.withMetadata(metadata);
    }

    private static Set<String> topics(ServiceKind kind) {
        return switch (kind) {
            case CENTER -> Set.of(
                    RegistryOperations.REGISTER,
                    RegistryOperations.HEARTBEAT,
                    RegistryOperations.UNREGISTER,
                    RegistryOperations.LIST,
                    RegistryOperations.SUBSCRIBE,
                    RegistryOperations.UNSUBSCRIBE,
                    RegistryOperations.REPLAY,
                    AgentDirectoryOperations.CLAIM,
                    AgentDirectoryOperations.MOVE,
                    AgentDirectoryOperations.UNBIND,
                    AgentDirectoryOperations.LOCATE,
                    ClusterEventOperations.SUBSCRIBE,
                    ClusterEventOperations.UNSUBSCRIBE,
                    ClusterEventOperations.PUBLISH,
                    GameConfigOperations.SNAPSHOT,
                    ShopStockOperations.RESERVE,
                    ShopStockOperations.RELEASE,
                    ShopStockOperations.REMAINING
            );
            case REGION -> Set.of("region.route", "region.heartbeat");
            case GAME -> Set.of(
                    "game.resume",
                    "game.heartbeat",
                    AllianceSnapshotOperations.GET,
                    FriendSnapshotOperations.GET,
                    BusinessAgentRpcOperations.DISPATCH,
                    PlayerBusinessRpcOperations.DISPATCH,
                    ProfileSnapshotOperations.GET,
                    AgentMigrationOperations.ACCEPT
            );
            case CHAT -> Set.of(
                    ChatOperations.JOIN_CHANNEL,
                    ChatOperations.LEAVE_CHANNEL,
                    ChatOperations.SEND_CHANNEL,
                    ChatOperations.JOIN_WORLD,
                    ChatOperations.LEAVE_WORLD,
                    ChatOperations.SEND_WORLD,
                    ChatOperations.JOIN_ALLIANCE,
                    ChatOperations.LEAVE_ALLIANCE,
                    ChatOperations.SEND_ALLIANCE,
                    ChatOperations.SEND_DIRECT,
                    BusinessAgentRpcOperations.DISPATCH
            );
            case SCENE -> Set.of(
                    SceneOperations.ENTER,
                    SceneOperations.LEAVE,
                    SceneOperations.MESSAGE,
                    BusinessAgentRpcOperations.DISPATCH
            );
            case PROXY -> Set.of("proxy.forward", "proxy.heartbeat");
        };
    }
}
