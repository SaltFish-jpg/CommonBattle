package com.commonbattle.observability;

import com.commonbattle.actor.rpc.ActorRpcClient;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.migration.AgentMigrationCoordinator;
import com.commonbattle.actor.agent.migration.AgentMigrationExecutor;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoveryService;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoveryScheduler;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStore;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskRetentionService;
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
import com.commonbattle.game.event.VersionedEventOutbox;
import com.commonbattle.game.event.VersionedEventOutboxReplayScheduler;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.player.PlayerAgentDrainService;
import com.commonbattle.game.player.PlayerAutoSaveScheduler;
import com.commonbattle.game.player.PlayerGameAgentManager;
import com.commonbattle.game.profile.ProfileInterestView;
import com.commonbattle.game.profile.ProfileRuntimeView;
import com.commonbattle.game.session.PlayerCommandAuditView;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.scene.SceneRuntimeView;
import com.commonbattle.game.shop.ShopRuntimeView;
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
    private final CopyOnWriteArrayList<AgentLifecycleManager> lifecycleManagers = new CopyOnWriteArrayList<>();
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
    private final CopyOnWriteArrayList<ProfileRuntimeView> profileRuntimes = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SceneRuntimeView> sceneRuntimes = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ShopRuntimeView> shopRuntimes = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerGameAgentManager> playerAgentManagers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerAutoSaveScheduler> playerAutoSaves = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerAgentDrainService> playerAgentDrains = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<DrainableComponent> drainableComponents = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ServiceDescriptorPublisher> serviceDescriptorPublishers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AgentMigrationCoordinator> migrationCoordinators = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AgentMigrationExecutor> migrationExecutors = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AgentMigrationRecoveryService> migrationRecoveries = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AgentMigrationRecoveryScheduler> migrationRecoverySchedulers =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AgentMigrationTaskRetentionService> migrationTaskRetentions =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AgentMigrationTaskStore> migrationTaskStores = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<VersionedEventOutbox> outboxes = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<VersionedEventOutboxReplayScheduler> outboxReplaySchedulers =
            new CopyOnWriteArrayList<>();

    public void register(Object component) {
        Objects.requireNonNull(component, "component");
        if (component instanceof Collection<?> collection) {
            collection.forEach(this::register);
            return;
        }
        addIf(component, ClusterRpcGateway.class, rpcGateways);
        addIf(component, ResilientRpcGateway.class, resilientRpcGateways);
        addIf(component, ActorRpcClient.class, actorRpcClients);
        addIf(component, AgentLifecycleManager.class, lifecycleManagers);
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
        addIf(component, ProfileRuntimeView.class, profileRuntimes);
        addIf(component, SceneRuntimeView.class, sceneRuntimes);
        addIf(component, ShopRuntimeView.class, shopRuntimes);
        addIf(component, PlayerGameAgentManager.class, playerAgentManagers);
        addIf(component, PlayerAutoSaveScheduler.class, playerAutoSaves);
        addIf(component, PlayerAgentDrainService.class, playerAgentDrains);
        addIf(component, DrainableComponent.class, drainableComponents);
        addIf(component, ServiceDescriptorPublisher.class, serviceDescriptorPublishers);
        addIf(component, AgentMigrationCoordinator.class, migrationCoordinators);
        addIf(component, AgentMigrationExecutor.class, migrationExecutors);
        addIf(component, AgentMigrationRecoveryService.class, migrationRecoveries);
        addIf(component, AgentMigrationRecoveryScheduler.class, migrationRecoverySchedulers);
        addIf(component, AgentMigrationTaskRetentionService.class, migrationTaskRetentions);
        addIf(component, AgentMigrationTaskStore.class, migrationTaskStores);
        addIf(component, VersionedEventOutbox.class, outboxes);
        addIf(component, VersionedEventOutboxReplayScheduler.class, outboxReplaySchedulers);
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

    public List<AgentLifecycleManager> lifecycleManagers() {
        return List.copyOf(lifecycleManagers);
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

    public List<ProfileRuntimeView> profileRuntimes() {
        return List.copyOf(profileRuntimes);
    }

    public List<SceneRuntimeView> sceneRuntimes() {
        return List.copyOf(sceneRuntimes);
    }

    public List<ShopRuntimeView> shopRuntimes() {
        return List.copyOf(shopRuntimes);
    }

    public List<PlayerGameAgentManager> playerAgentManagers() {
        return List.copyOf(playerAgentManagers);
    }

    public List<PlayerAutoSaveScheduler> playerAutoSaves() {
        return List.copyOf(playerAutoSaves);
    }

    public List<PlayerAgentDrainService> playerAgentDrains() {
        return List.copyOf(playerAgentDrains);
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

    public List<AgentMigrationCoordinator> migrationCoordinators() {
        return List.copyOf(migrationCoordinators);
    }

    public List<AgentMigrationExecutor> migrationExecutors() {
        return List.copyOf(migrationExecutors);
    }

    public List<AgentMigrationRecoveryService> migrationRecoveries() {
        return List.copyOf(migrationRecoveries);
    }

    public List<AgentMigrationRecoveryScheduler> migrationRecoverySchedulers() {
        return List.copyOf(migrationRecoverySchedulers);
    }

    public List<AgentMigrationTaskRetentionService> migrationTaskRetentions() {
        return List.copyOf(migrationTaskRetentions);
    }

    public List<AgentMigrationTaskStore> migrationTaskStores() {
        return List.copyOf(migrationTaskStores);
    }

    public List<VersionedEventOutbox> outboxes() {
        return List.copyOf(outboxes);
    }

    public List<VersionedEventOutboxReplayScheduler> outboxReplaySchedulers() {
        return List.copyOf(outboxReplaySchedulers);
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
