package com.commonbattle.observability;

import com.commonbattle.actor.rpc.ActorRpcClient;
import com.commonbattle.cluster.ClusterNode;
import com.commonbattle.cluster.event.ClusterEventCenter;
import com.commonbattle.cluster.event.ClusterEventSubscriptionManager;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.registry.RegistryLeaseReaper;
import com.commonbattle.cluster.registry.RegistryLeaseRenewer;
import com.commonbattle.cluster.registry.ServiceDescriptorPublisher;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.ResilientRpcGateway;
import com.commonbattle.game.config.GameConfigAutoRecovery;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.profile.ProfileInterestView;
import com.commonbattle.game.session.PlayerCommandAuditView;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.runtime.DrainableComponent;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 运行时健康组件注册表。
 * 启动流程把组件登记一次，健康探针按类型聚合指标，避免启动类维护很长的组件参数列表。
 */
public final class RuntimeHealthRegistry {
    private final CopyOnWriteArrayList<ClusterRpcGateway> rpcGateways = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ResilientRpcGateway> resilientRpcGateways = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ActorRpcClient> actorRpcClients = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerCommandDispatcher> commandDispatchers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NettyClusterTransport> networkTransports = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RegistryLeaseRenewer> leaseRenewers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ClusterNode> clusterNodes = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RegistryLeaseReaper> leaseReapers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<LocalGameConfigCache> configCaches = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<GameConfigAutoRecovery> configRecoveries = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerCommandAuditView> commandAudits = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ClusterEventCenter> eventCenters = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ClusterEventSubscriptionManager> eventSubscriptions = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ProfileInterestView> profileInterests = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<DrainableComponent> drainableComponents = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ServiceDescriptorPublisher> serviceDescriptorPublishers = new CopyOnWriteArrayList<>();

    public void register(Object component) {
        Objects.requireNonNull(component, "component");
        if (component instanceof Collection<?> collection) {
            collection.forEach(this::register);
            return;
        }
        addIf(component, ClusterRpcGateway.class, rpcGateways);
        addIf(component, ResilientRpcGateway.class, resilientRpcGateways);
        addIf(component, ActorRpcClient.class, actorRpcClients);
        addIf(component, PlayerCommandDispatcher.class, commandDispatchers);
        addIf(component, NettyClusterTransport.class, networkTransports);
        addIf(component, RegistryLeaseRenewer.class, leaseRenewers);
        addIf(component, ClusterNode.class, clusterNodes);
        addIf(component, RegistryLeaseReaper.class, leaseReapers);
        addIf(component, LocalGameConfigCache.class, configCaches);
        addIf(component, GameConfigAutoRecovery.class, configRecoveries);
        addIf(component, PlayerCommandAuditView.class, commandAudits);
        addIf(component, ClusterEventCenter.class, eventCenters);
        addIf(component, ClusterEventSubscriptionManager.class, eventSubscriptions);
        addIf(component, ProfileInterestView.class, profileInterests);
        addIf(component, DrainableComponent.class, drainableComponents);
        addIf(component, ServiceDescriptorPublisher.class, serviceDescriptorPublishers);
    }

    public List<ClusterRpcGateway> rpcGateways() {
        return List.copyOf(rpcGateways);
    }

    public List<ResilientRpcGateway> resilientRpcGateways() {
        return List.copyOf(resilientRpcGateways);
    }

    public List<ActorRpcClient> actorRpcClients() {
        return List.copyOf(actorRpcClients);
    }

    public List<PlayerCommandDispatcher> commandDispatchers() {
        return List.copyOf(commandDispatchers);
    }

    public List<NettyClusterTransport> networkTransports() {
        return List.copyOf(networkTransports);
    }

    public List<RegistryLeaseRenewer> leaseRenewers() {
        List<RegistryLeaseRenewer> fromNodes = clusterNodes.stream()
                .flatMap(node -> node.leaseRenewer().stream())
                .toList();
        if (fromNodes.isEmpty()) {
            return List.copyOf(leaseRenewers);
        }
        CopyOnWriteArrayList<RegistryLeaseRenewer> all = new CopyOnWriteArrayList<>(leaseRenewers);
        all.addAll(fromNodes);
        return List.copyOf(all);
    }

    public List<RegistryLeaseReaper> leaseReapers() {
        return List.copyOf(leaseReapers);
    }

    public List<LocalGameConfigCache> configCaches() {
        return List.copyOf(configCaches);
    }

    public List<GameConfigAutoRecovery> configRecoveries() {
        return List.copyOf(configRecoveries);
    }

    public List<PlayerCommandAuditView> commandAudits() {
        return List.copyOf(commandAudits);
    }

    public List<ClusterEventCenter> eventCenters() {
        return List.copyOf(eventCenters);
    }

    public List<ClusterEventSubscriptionManager> eventSubscriptions() {
        return List.copyOf(eventSubscriptions);
    }

    public List<ProfileInterestView> profileInterests() {
        return List.copyOf(profileInterests);
    }

    public List<DrainableComponent> drainableComponents() {
        List<DrainableComponent> fromNodes = clusterNodes.stream()
                .flatMap(node -> node.leaseRenewer().stream())
                .map(DrainableComponent.class::cast)
                .toList();
        CopyOnWriteArrayList<DrainableComponent> all = new CopyOnWriteArrayList<>(fromNodes);
        all.addAll(drainableComponents);
        return List.copyOf(all);
    }

    public List<ServiceDescriptorPublisher> serviceDescriptorPublishers() {
        return List.copyOf(serviceDescriptorPublishers);
    }

    private static <T> void addIf(Object component, Class<T> type, CopyOnWriteArrayList<T> target) {
        if (type.isInstance(component)) {
            T casted = type.cast(component);
            if (!target.contains(casted)) {
                target.add(casted);
            }
        }
    }
}
