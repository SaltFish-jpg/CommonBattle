package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.migration.AgentMigrationCoordinator;
import com.commonbattle.actor.agent.migration.AgentMigrationCoordinatorStats;
import com.commonbattle.actor.agent.migration.AgentMigrationExecutor;
import com.commonbattle.actor.agent.migration.AgentMigrationExecutorStats;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoveryService;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoveryScheduler;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoverySchedulerStats;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoveryStats;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStore;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStoreStats;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskRetentionService;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskRetentionStats;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;
import com.commonbattle.actor.rpc.ActorRpcClient;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.cluster.event.ClusterEventCenter;
import com.commonbattle.cluster.event.ClusterEventCenterStats;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.netty.NettyTransportStats;
import com.commonbattle.cluster.registry.RegistryHistoryStats;
import com.commonbattle.cluster.registry.RegistryHistoryView;
import com.commonbattle.cluster.registry.RegistrySubscriptionStats;
import com.commonbattle.cluster.registry.RegistrySubscriptionView;
import com.commonbattle.cluster.registry.RegistryLeaseReaper;
import com.commonbattle.cluster.registry.RegistryLeaseRenewer;
import com.commonbattle.cluster.registry.RegistryLeaseRenewalStats;
import com.commonbattle.cluster.registry.RemoteRegistryRecoveryStats;
import com.commonbattle.cluster.registry.RemoteRegistryRecoveryView;
import com.commonbattle.cluster.registry.ServiceDescriptorPublisher;
import com.commonbattle.cluster.registry.ServiceDescriptorPublisherStats;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcGatewayStats;
import com.commonbattle.cluster.rpc.RpcRoutePolicyView;
import com.commonbattle.cluster.rpc.RpcRouteStats;
import com.commonbattle.cluster.rpc.ResilientRpcGateway;
import com.commonbattle.cluster.event.ClusterEventSubscriptionManager;
import com.commonbattle.cluster.event.ClusterEventSubscriptionStats;
import com.commonbattle.cluster.event.ClusterEventTopicStats;
import com.commonbattle.game.config.GameConfigAutoRecovery;
import com.commonbattle.game.config.GameConfigAutoRecoveryStats;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.event.ActorEventSubscriberStats;
import com.commonbattle.game.event.ActorEventSubscriberView;
import com.commonbattle.game.event.OwnerActorEventSubscriptionStats;
import com.commonbattle.game.event.OwnerActorEventSubscriptionView;
import com.commonbattle.game.event.PendingVersionedEvent;
import com.commonbattle.game.event.VersionedEventOutbox;
import com.commonbattle.game.chat.ChatRuntimeView;
import com.commonbattle.game.chat.ChatServiceStats;
import com.commonbattle.game.player.PlayerAgentDrainService;
import com.commonbattle.game.player.PlayerAutoSaveScheduler;
import com.commonbattle.game.player.AsyncShopPurchaseStats;
import com.commonbattle.game.player.AsyncShopPurchaseView;
import com.commonbattle.game.player.PlayerAutoSaveStats;
import com.commonbattle.game.player.PlayerBusinessResponseStats;
import com.commonbattle.game.player.PlayerBusinessResponseView;
import com.commonbattle.game.player.PlayerGatewayView;
import com.commonbattle.game.player.NettyPlayerGatewayStats;
import com.commonbattle.game.player.PlayerGameAgentManager;
import com.commonbattle.game.profile.ProfileInterestStats;
import com.commonbattle.game.profile.ProfileInterestView;
import com.commonbattle.game.profile.ProfileRuntimeStats;
import com.commonbattle.game.profile.ProfileRuntimeView;
import com.commonbattle.game.scene.SceneRuntimeStats;
import com.commonbattle.game.scene.SceneRuntimeView;
import com.commonbattle.game.shop.ShopRuntimeStats;
import com.commonbattle.game.shop.ShopRuntimeView;
import com.commonbattle.game.session.PlayerCommandAuditOutcome;
import com.commonbattle.game.session.PlayerCommandAuditRecord;
import com.commonbattle.game.session.PlayerCommandAuditStats;
import com.commonbattle.game.session.PlayerCommandAuditView;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandStats;
import com.commonbattle.game.session.PlayerOutboundDeliveryStats;
import com.commonbattle.game.session.PlayerOutboundDeliveryView;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 运行时健康探针。
 * 它只读取框架组件快照，不干预 actor 调度、RPC 路由或业务状态。
 */
public final class RuntimeHealthProbe {
    private final Clock clock;
    private final ActorSystem actors;
    private final AgentLifecycleManager lifecycles;
    private final VersionedEventOutbox outbox;
    private final ClusterDirectory directory;
    private final Collection<ClusterRpcGateway> rpcGateways;
    private final Collection<ResilientRpcGateway> resilientRpcGateways;
    private Collection<RpcRoutePolicyView> rpcRoutePolicies = List.of();
    private final Collection<ActorRpcClient> actorRpcClients;
    private final Collection<PlayerCommandDispatcher> commandDispatchers;
    private final Collection<NettyClusterTransport> networkTransports;
    private final Collection<RegistryLeaseRenewer> leaseRenewers;
    private final Collection<RegistryLeaseReaper> leaseReapers;
    private Collection<RegistryHistoryView> registryHistories = List.of();
    private Collection<RegistrySubscriptionView> registrySubscriptions = List.of();
    private Collection<RemoteRegistryRecoveryView> remoteRegistryRecoveries = List.of();
    private final Collection<LocalGameConfigCache> configCaches;
    private final Collection<GameConfigAutoRecovery> configRecoveries;
    private final Collection<PlayerCommandAuditView> commandAudits;
    private final Collection<ClusterEventCenter> eventCenters;
    private final Collection<ClusterEventSubscriptionManager> eventSubscriptions;
    private Collection<ActorEventSubscriberView> actorEventSubscribers = List.of();
    private Collection<OwnerActorEventSubscriptionView> ownerActorEventSubscriptions = List.of();
    private final Collection<ProfileInterestView> profileInterests;
    private final Collection<ProfileRuntimeView> profileRuntimes;
    private Collection<ChatRuntimeView> chatRuntimes = List.of();
    private Collection<SceneRuntimeView> sceneRuntimes = List.of();
    private final Collection<ShopRuntimeView> shopRuntimes;
    private Collection<PlayerGameAgentManager> playerAgentManagers = List.of();
    private Collection<PlayerAutoSaveScheduler> playerAutoSaves = List.of();
    private Collection<PlayerAgentDrainService> playerAgentDrains = List.of();
    private Collection<PlayerBusinessResponseView> playerBusinessResponses = List.of();
    private Collection<PlayerOutboundDeliveryView> playerOutboundDeliveries = List.of();
    private Collection<PlayerGatewayView> playerGateways = List.of();
    private Collection<AsyncShopPurchaseView> asyncShopPurchases = List.of();
    private final Collection<ServiceDescriptorPublisher> serviceDescriptorPublishers;
    private final Collection<AgentMigrationCoordinator> migrationCoordinators;
    private final Collection<AgentMigrationExecutor> migrationExecutors;
    private final Collection<AgentMigrationRecoveryService> migrationRecoveries;
    private final Collection<AgentMigrationRecoveryScheduler> migrationRecoverySchedulers;
    private final Collection<AgentMigrationTaskRetentionService> migrationTaskRetentions;
    private final Collection<AgentMigrationTaskStore> migrationTaskStores;
    private final RuntimeHealthPolicy policy;

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            RuntimeHealthPolicy policy
    ) {
        this(clock, actors, lifecycles, outbox, directory, List.of(), policy);
    }

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            RuntimeHealthRegistry registry,
            RuntimeHealthPolicy policy
    ) {
        this(clock, actors, lifecycles, outbox, directory,
                registry.rpcGateways(),
                registry.commandDispatchers(),
                registry.networkTransports(),
                registry.leaseRenewers(),
                registry.leaseReapers(),
                registry.configCaches(),
                registry.configRecoveries(),
                registry.commandAudits(),
                registry.eventCenters(),
                registry.eventSubscriptions(),
                registry.profileInterests(),
                registry.profileRuntimes(),
                registry.shopRuntimes(),
                registry.resilientRpcGateways(),
                registry.actorRpcClients(),
                registry.serviceDescriptorPublishers(),
                registry.migrationCoordinators(),
                registry.migrationExecutors(),
                registry.migrationRecoveries(),
                registry.migrationRecoverySchedulers(),
                registry.migrationTaskRetentions(),
                registry.migrationTaskStores(),
                policy);
        this.playerAgentManagers = registry.playerAgentManagers();
        this.playerAutoSaves = registry.playerAutoSaves();
        this.playerAgentDrains = registry.playerAgentDrains();
        this.playerBusinessResponses = registry.playerBusinessResponses();
        this.playerOutboundDeliveries = registry.playerOutboundDeliveries();
        this.playerGateways = registry.playerGateways();
        this.asyncShopPurchases = registry.asyncShopPurchases();
        this.rpcRoutePolicies = registry.rpcRoutePolicies();
        this.sceneRuntimes = registry.sceneRuntimes();
        this.chatRuntimes = registry.chatRuntimes();
        this.actorEventSubscribers = registry.actorEventSubscribers();
        this.ownerActorEventSubscriptions = registry.ownerActorEventSubscriptions();
        this.registryHistories = registry.registryHistories();
        this.registrySubscriptions = registry.registrySubscriptions();
        this.remoteRegistryRecoveries = registry.remoteRegistryRecoveries();
    }

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            Collection<ClusterRpcGateway> rpcGateways,
            RuntimeHealthPolicy policy
    ) {
        this(clock, actors, lifecycles, outbox, directory, rpcGateways, List.of(), policy);
    }

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            Collection<ClusterRpcGateway> rpcGateways,
            Collection<PlayerCommandDispatcher> commandDispatchers,
            RuntimeHealthPolicy policy
    ) {
        this(clock, actors, lifecycles, outbox, directory, rpcGateways, commandDispatchers, List.of(), policy);
    }

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            Collection<ClusterRpcGateway> rpcGateways,
            Collection<PlayerCommandDispatcher> commandDispatchers,
            Collection<LocalGameConfigCache> configCaches,
            RuntimeHealthPolicy policy
    ) {
        this(clock, actors, lifecycles, outbox, directory, rpcGateways, commandDispatchers,
                configCaches, List.of(), policy);
    }

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            Collection<ClusterRpcGateway> rpcGateways,
            Collection<PlayerCommandDispatcher> commandDispatchers,
            Collection<LocalGameConfigCache> configCaches,
            Collection<GameConfigAutoRecovery> configRecoveries,
            RuntimeHealthPolicy policy
    ) {
        this(clock, actors, lifecycles, outbox, directory, rpcGateways, commandDispatchers,
                configCaches, configRecoveries, List.of(), policy);
    }

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            Collection<ClusterRpcGateway> rpcGateways,
            Collection<PlayerCommandDispatcher> commandDispatchers,
            Collection<LocalGameConfigCache> configCaches,
            Collection<GameConfigAutoRecovery> configRecoveries,
            Collection<PlayerCommandAuditView> commandAudits,
            RuntimeHealthPolicy policy
    ) {
        this(clock, actors, lifecycles, outbox, directory, rpcGateways, commandDispatchers,
                List.of(), List.of(), List.of(), configCaches, configRecoveries, commandAudits, policy);
    }

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            Collection<ClusterRpcGateway> rpcGateways,
            Collection<PlayerCommandDispatcher> commandDispatchers,
            Collection<RegistryLeaseRenewer> leaseRenewers,
            Collection<RegistryLeaseReaper> leaseReapers,
            Collection<LocalGameConfigCache> configCaches,
            Collection<GameConfigAutoRecovery> configRecoveries,
            Collection<PlayerCommandAuditView> commandAudits,
            RuntimeHealthPolicy policy
    ) {
        this(clock, actors, lifecycles, outbox, directory, rpcGateways, commandDispatchers,
                List.of(), leaseRenewers, leaseReapers, configCaches, configRecoveries, commandAudits,
                List.of(), List.of(), policy);
    }

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            Collection<ClusterRpcGateway> rpcGateways,
            Collection<PlayerCommandDispatcher> commandDispatchers,
            Collection<NettyClusterTransport> networkTransports,
            Collection<RegistryLeaseRenewer> leaseRenewers,
            Collection<RegistryLeaseReaper> leaseReapers,
            Collection<LocalGameConfigCache> configCaches,
            Collection<GameConfigAutoRecovery> configRecoveries,
            Collection<PlayerCommandAuditView> commandAudits,
            RuntimeHealthPolicy policy
    ) {
        this(clock, actors, lifecycles, outbox, directory, rpcGateways, commandDispatchers, networkTransports,
                leaseRenewers, leaseReapers, configCaches, configRecoveries, commandAudits, List.of(), policy);
    }

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            Collection<ClusterRpcGateway> rpcGateways,
            Collection<PlayerCommandDispatcher> commandDispatchers,
            Collection<NettyClusterTransport> networkTransports,
            Collection<RegistryLeaseRenewer> leaseRenewers,
            Collection<RegistryLeaseReaper> leaseReapers,
            Collection<LocalGameConfigCache> configCaches,
            Collection<GameConfigAutoRecovery> configRecoveries,
            Collection<PlayerCommandAuditView> commandAudits,
            Collection<ClusterEventSubscriptionManager> eventSubscriptions,
            RuntimeHealthPolicy policy
    ) {
        this(clock, actors, lifecycles, outbox, directory, rpcGateways, commandDispatchers, networkTransports,
                leaseRenewers, leaseReapers, configCaches, configRecoveries, commandAudits, List.of(),
                eventSubscriptions, policy);
    }

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            Collection<ClusterRpcGateway> rpcGateways,
            Collection<PlayerCommandDispatcher> commandDispatchers,
            Collection<NettyClusterTransport> networkTransports,
            Collection<RegistryLeaseRenewer> leaseRenewers,
            Collection<RegistryLeaseReaper> leaseReapers,
            Collection<LocalGameConfigCache> configCaches,
            Collection<GameConfigAutoRecovery> configRecoveries,
            Collection<PlayerCommandAuditView> commandAudits,
            Collection<ClusterEventCenter> eventCenters,
            Collection<ClusterEventSubscriptionManager> eventSubscriptions,
            RuntimeHealthPolicy policy
    ) {
        this(clock, actors, lifecycles, outbox, directory, rpcGateways, commandDispatchers, networkTransports,
                leaseRenewers, leaseReapers, configCaches, configRecoveries, commandAudits, eventCenters,
                eventSubscriptions, List.of(), policy);
    }

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            Collection<ClusterRpcGateway> rpcGateways,
            Collection<PlayerCommandDispatcher> commandDispatchers,
            Collection<NettyClusterTransport> networkTransports,
            Collection<RegistryLeaseRenewer> leaseRenewers,
            Collection<RegistryLeaseReaper> leaseReapers,
            Collection<LocalGameConfigCache> configCaches,
            Collection<GameConfigAutoRecovery> configRecoveries,
            Collection<PlayerCommandAuditView> commandAudits,
            Collection<ClusterEventCenter> eventCenters,
            Collection<ClusterEventSubscriptionManager> eventSubscriptions,
            Collection<ProfileInterestView> profileInterests,
            RuntimeHealthPolicy policy
    ) {
        this(clock, actors, lifecycles, outbox, directory, rpcGateways, commandDispatchers, networkTransports,
                leaseRenewers, leaseReapers, configCaches, configRecoveries, commandAudits, eventCenters,
                eventSubscriptions, profileInterests, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), policy);
    }

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            Collection<ClusterRpcGateway> rpcGateways,
            Collection<PlayerCommandDispatcher> commandDispatchers,
            Collection<NettyClusterTransport> networkTransports,
            Collection<RegistryLeaseRenewer> leaseRenewers,
            Collection<RegistryLeaseReaper> leaseReapers,
            Collection<LocalGameConfigCache> configCaches,
            Collection<GameConfigAutoRecovery> configRecoveries,
            Collection<PlayerCommandAuditView> commandAudits,
            Collection<ClusterEventCenter> eventCenters,
            Collection<ClusterEventSubscriptionManager> eventSubscriptions,
            Collection<ProfileInterestView> profileInterests,
            Collection<ResilientRpcGateway> resilientRpcGateways,
            RuntimeHealthPolicy policy
    ) {
        this(clock, actors, lifecycles, outbox, directory, rpcGateways, commandDispatchers, networkTransports,
                leaseRenewers, leaseReapers, configCaches, configRecoveries, commandAudits, eventCenters,
                eventSubscriptions, profileInterests, List.of(), List.of(), resilientRpcGateways, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), policy);
    }

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            Collection<ClusterRpcGateway> rpcGateways,
            Collection<PlayerCommandDispatcher> commandDispatchers,
            Collection<NettyClusterTransport> networkTransports,
            Collection<RegistryLeaseRenewer> leaseRenewers,
            Collection<RegistryLeaseReaper> leaseReapers,
            Collection<LocalGameConfigCache> configCaches,
            Collection<GameConfigAutoRecovery> configRecoveries,
            Collection<PlayerCommandAuditView> commandAudits,
            Collection<ClusterEventCenter> eventCenters,
            Collection<ClusterEventSubscriptionManager> eventSubscriptions,
            Collection<ProfileInterestView> profileInterests,
            Collection<ResilientRpcGateway> resilientRpcGateways,
            Collection<ActorRpcClient> actorRpcClients,
            RuntimeHealthPolicy policy
    ) {
        this(clock, actors, lifecycles, outbox, directory, rpcGateways, commandDispatchers, networkTransports,
                leaseRenewers, leaseReapers, configCaches, configRecoveries, commandAudits, eventCenters,
                eventSubscriptions, profileInterests, List.of(), List.of(), resilientRpcGateways, actorRpcClients, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), policy);
    }

    public RuntimeHealthProbe(
            Clock clock,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            VersionedEventOutbox outbox,
            ClusterDirectory directory,
            Collection<ClusterRpcGateway> rpcGateways,
            Collection<PlayerCommandDispatcher> commandDispatchers,
            Collection<NettyClusterTransport> networkTransports,
            Collection<RegistryLeaseRenewer> leaseRenewers,
            Collection<RegistryLeaseReaper> leaseReapers,
            Collection<LocalGameConfigCache> configCaches,
            Collection<GameConfigAutoRecovery> configRecoveries,
            Collection<PlayerCommandAuditView> commandAudits,
            Collection<ClusterEventCenter> eventCenters,
            Collection<ClusterEventSubscriptionManager> eventSubscriptions,
            Collection<ProfileInterestView> profileInterests,
            Collection<ProfileRuntimeView> profileRuntimes,
            Collection<ShopRuntimeView> shopRuntimes,
            Collection<ResilientRpcGateway> resilientRpcGateways,
            Collection<ActorRpcClient> actorRpcClients,
            Collection<ServiceDescriptorPublisher> serviceDescriptorPublishers,
            Collection<AgentMigrationCoordinator> migrationCoordinators,
            Collection<AgentMigrationExecutor> migrationExecutors,
            Collection<AgentMigrationRecoveryService> migrationRecoveries,
            Collection<AgentMigrationRecoveryScheduler> migrationRecoverySchedulers,
            Collection<AgentMigrationTaskRetentionService> migrationTaskRetentions,
            Collection<AgentMigrationTaskStore> migrationTaskStores,
            RuntimeHealthPolicy policy
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.lifecycles = Objects.requireNonNull(lifecycles, "lifecycles");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.rpcGateways = List.copyOf(Objects.requireNonNull(rpcGateways, "rpcGateways"));
        this.resilientRpcGateways = List.copyOf(Objects.requireNonNull(resilientRpcGateways, "resilientRpcGateways"));
        this.commandDispatchers = List.copyOf(Objects.requireNonNull(commandDispatchers, "commandDispatchers"));
        this.networkTransports = List.copyOf(Objects.requireNonNull(networkTransports, "networkTransports"));
        this.leaseRenewers = List.copyOf(Objects.requireNonNull(leaseRenewers, "leaseRenewers"));
        this.leaseReapers = List.copyOf(Objects.requireNonNull(leaseReapers, "leaseReapers"));
        this.configCaches = List.copyOf(Objects.requireNonNull(configCaches, "configCaches"));
        this.configRecoveries = List.copyOf(Objects.requireNonNull(configRecoveries, "configRecoveries"));
        this.commandAudits = List.copyOf(Objects.requireNonNull(commandAudits, "commandAudits"));
        this.eventCenters = List.copyOf(Objects.requireNonNull(eventCenters, "eventCenters"));
        this.eventSubscriptions = List.copyOf(Objects.requireNonNull(eventSubscriptions, "eventSubscriptions"));
        this.profileInterests = List.copyOf(Objects.requireNonNull(profileInterests, "profileInterests"));
        this.profileRuntimes = List.copyOf(Objects.requireNonNull(profileRuntimes, "profileRuntimes"));
        this.shopRuntimes = List.copyOf(Objects.requireNonNull(shopRuntimes, "shopRuntimes"));
        this.actorRpcClients = List.copyOf(Objects.requireNonNull(actorRpcClients, "actorRpcClients"));
        this.serviceDescriptorPublishers = List.copyOf(Objects.requireNonNull(serviceDescriptorPublishers, "serviceDescriptorPublishers"));
        this.migrationCoordinators = List.copyOf(Objects.requireNonNull(migrationCoordinators, "migrationCoordinators"));
        this.migrationExecutors = List.copyOf(Objects.requireNonNull(migrationExecutors, "migrationExecutors"));
        this.migrationRecoveries = List.copyOf(Objects.requireNonNull(migrationRecoveries, "migrationRecoveries"));
        this.migrationRecoverySchedulers = List.copyOf(Objects.requireNonNull(migrationRecoverySchedulers, "migrationRecoverySchedulers"));
        this.migrationTaskRetentions = List.copyOf(Objects.requireNonNull(migrationTaskRetentions, "migrationTaskRetentions"));
        this.migrationTaskStores = List.copyOf(Objects.requireNonNull(migrationTaskStores, "migrationTaskStores"));
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public RuntimeHealthSnapshot snapshot() {
        var actorStats = actors.stats();
        RpcGatewayStats rpcStats = rpcStats();
        RpcResilienceHealthStats rpcResilienceStats = rpcResilienceStats();
        RpcRouteHealthStats rpcRouteStats = rpcRouteStats();
        ActorRpcHealthStats actorRpcStats = actorRpcStats();
        PlayerCommandStats commandStats = commandStats();
        PlayerBusinessResponseHealthStats businessResponseStats = businessResponseStats();
        PlayerOutboundDeliveryHealthStats playerOutboundDeliveryStats = playerOutboundDeliveryStats();
        PlayerGatewayHealthStats playerGatewayStats = playerGatewayStats();
        AsyncShopPurchaseHealthStats asyncShopPurchaseStats = asyncShopPurchaseStats();
        AgentLifecycleStats agentStats = agentStats();
        PlayerAgentHealthStats playerAgentStats = playerAgentStats();
        AgentMigrationCoordinatorStats migrationStats = migrationStats();
        AgentMigrationExecutorStats migrationExecutorStats = migrationExecutorStats();
        AgentMigrationRecoveryStats migrationRecoveryStats = migrationRecoveryStats();
        AgentMigrationRecoverySchedulerStats migrationRecoverySchedulerStats = migrationRecoverySchedulerStats();
        AgentMigrationTaskRetentionStats migrationTaskRetentionStats = migrationTaskRetentionStats();
        AgentMigrationTaskStoreStats migrationTaskStoreStats = migrationTaskStoreStats();
        EventOutboxStats outboxStats = outboxStats();
        ClusterServiceStats clusterStats = clusterStats();
        RegistryLeaseHealthStats leaseStats = leaseStats();
        RegistryHistoryHealthStats registryHistoryStats = registryHistoryStats();
        RegistrySubscriptionHealthStats registrySubscriptionStats = registrySubscriptionStats();
        RemoteRegistryRecoveryHealthStats remoteRegistryRecoveryStats = remoteRegistryRecoveryStats();
        ServiceDescriptorPublisherHealthStats descriptorPublisherStats = serviceDescriptorPublisherStats();
        NetworkTransportHealthStats networkStats = networkStats();
        ConfigCacheHealthStats configStats = configStats();
        ConfigRecoveryHealthStats recoveryStats = configRecoveryStats();
        PlayerCommandAuditHealthStats auditStats = commandAuditStats();
        EventCenterHealthStats eventCenterStats = eventCenterStats();
        EventSubscriptionHealthStats eventSubscriptionStats = eventSubscriptionStats();
        ActorEventSubscriberHealthStats actorEventSubscriberStats = actorEventSubscriberStats();
        OwnerActorEventSubscriptionHealthStats ownerActorEventSubscriptionStats = ownerActorEventSubscriptionStats();
        ProfileInterestHealthStats profileInterestStats = profileInterestStats();
        ProfileRuntimeHealthStats profileRuntimeStats = profileRuntimeStats();
        ChatRuntimeHealthStats chatRuntimeStats = chatRuntimeStats();
        SceneRuntimeHealthStats sceneRuntimeStats = sceneRuntimeStats();
        ShopRuntimeHealthStats shopRuntimeStats = shopRuntimeStats();
        RuntimeHealthStatus status = status(
                actorStats.queuedTasks(),
                outboxStats.pendingEvents(),
                commandStats,
                businessResponseStats,
                asyncShopPurchaseStats,
                configStats,
                leaseStats,
                remoteRegistryRecoveryStats,
                descriptorPublisherStats,
                networkStats,
                eventCenterStats,
                eventSubscriptionStats,
                actorEventSubscriberStats,
                ownerActorEventSubscriptionStats,
                profileInterestStats,
                profileRuntimeStats,
                sceneRuntimeStats,
                shopRuntimeStats,
                playerAgentStats,
                migrationStats,
                migrationExecutorStats,
                migrationRecoveryStats,
                migrationRecoverySchedulerStats,
                migrationTaskRetentionStats,
                migrationTaskStoreStats
        );
        return new RuntimeHealthSnapshot(clock.instant(), status, actorStats, rpcStats, rpcResilienceStats, rpcRouteStats, actorRpcStats, commandStats, businessResponseStats, playerOutboundDeliveryStats, playerGatewayStats, asyncShopPurchaseStats, agentStats, playerAgentStats,
                migrationStats, migrationExecutorStats, migrationRecoveryStats, migrationRecoverySchedulerStats, migrationTaskRetentionStats, migrationTaskStoreStats, outboxStats, clusterStats, leaseStats, registryHistoryStats, registrySubscriptionStats, remoteRegistryRecoveryStats, descriptorPublisherStats, networkStats, configStats, recoveryStats,
                eventCenterStats, eventSubscriptionStats, actorEventSubscriberStats, ownerActorEventSubscriptionStats,
                profileInterestStats, profileRuntimeStats, chatRuntimeStats, sceneRuntimeStats, shopRuntimeStats, auditStats);
    }

    private RpcGatewayStats rpcStats() {
        return rpcGateways.stream()
                .map(ClusterRpcGateway::stats)
                .reduce(RpcGatewayStats.empty(), RpcGatewayStats::plus);
    }

    private RpcResilienceHealthStats rpcResilienceStats() {
        return resilientRpcGateways.stream()
                .map(gateway -> RpcResilienceHealthStats.from(gateway.stats()))
                .reduce(RpcResilienceHealthStats.empty(), RpcResilienceHealthStats::plus);
    }

    private RpcRouteHealthStats rpcRouteStats() {
        if (rpcRoutePolicies.isEmpty()) {
            return RpcRouteHealthStats.empty();
        }
        RpcRouteStats stats = rpcRoutePolicies.stream()
                .map(RpcRoutePolicyView::stats)
                .reduce(RpcRouteStats.empty(), RpcRouteStats::plus);
        return RpcRouteHealthStats.from(rpcRoutePolicies.size(), stats);
    }

    private ActorRpcHealthStats actorRpcStats() {
        return actorRpcClients.stream()
                .map(client -> ActorRpcHealthStats.from(client.stats()))
                .reduce(ActorRpcHealthStats.empty(), ActorRpcHealthStats::plus);
    }

    private PlayerCommandStats commandStats() {
        return commandDispatchers.stream()
                .map(PlayerCommandDispatcher::stats)
                .reduce(PlayerCommandStats.empty(), PlayerCommandStats::plus);
    }

    private PlayerBusinessResponseHealthStats businessResponseStats() {
        if (playerBusinessResponses.isEmpty()) {
            return PlayerBusinessResponseHealthStats.empty();
        }
        PlayerBusinessResponseStats stats = playerBusinessResponses.stream()
                .map(PlayerBusinessResponseView::stats)
                .reduce(PlayerBusinessResponseStats.empty(), PlayerBusinessResponseStats::plus);
        return PlayerBusinessResponseHealthStats.from(playerBusinessResponses.size(), stats);
    }

    private AsyncShopPurchaseHealthStats asyncShopPurchaseStats() {
        if (asyncShopPurchases.isEmpty()) {
            return AsyncShopPurchaseHealthStats.empty();
        }
        AsyncShopPurchaseStats stats = asyncShopPurchases.stream()
                .map(AsyncShopPurchaseView::stats)
                .reduce(AsyncShopPurchaseStats.empty(), AsyncShopPurchaseStats::plus);
        return AsyncShopPurchaseHealthStats.from(asyncShopPurchases.size(), stats);
    }

    private AgentLifecycleStats agentStats() {
        EnumMap<AgentLifecycleState, Integer> counts = new EnumMap<>(AgentLifecycleState.class);
        lifecycles.records().values().forEach(record ->
                counts.merge(record.state(), 1, Integer::sum));
        return new AgentLifecycleStats(counts);
    }

    private PlayerAgentHealthStats playerAgentStats() {
        int loadedAgents = playerAgentManagers.stream()
                .mapToInt(PlayerGameAgentManager::loadedAgents)
                .sum();
        long autoSaveRuns = 0;
        long autoSaveSubmitted = 0;
        long autoSaveCompleted = 0;
        long autoSaveFailedRuns = 0;
        long autoSaveFailedSaves = 0;
        for (PlayerAutoSaveScheduler scheduler : playerAutoSaves) {
            PlayerAutoSaveStats stats = scheduler.stats();
            autoSaveRuns += stats.runs();
            autoSaveSubmitted += stats.submitted();
            autoSaveCompleted += stats.completed();
            autoSaveFailedRuns += stats.failedRuns();
            autoSaveFailedSaves += stats.failedSaves();
        }
        long drainSubmitted = 0;
        long drainCompleted = 0;
        long drainFailedSaves = 0;
        int drainingServices = 0;
        for (PlayerAgentDrainService drain : playerAgentDrains) {
            drainSubmitted += drain.submitted();
            drainCompleted += drain.completed();
            drainFailedSaves += drain.failedSaves();
            if (drain.isDraining()) {
                drainingServices++;
            }
        }
        return new PlayerAgentHealthStats(
                playerAgentManagers.size(),
                loadedAgents,
                playerAutoSaves.size(),
                autoSaveRuns,
                autoSaveSubmitted,
                autoSaveCompleted,
                autoSaveFailedRuns,
                autoSaveFailedSaves,
                playerAgentDrains.size(),
                drainingServices,
                drainSubmitted,
                drainCompleted,
                drainFailedSaves
        );
    }

    private AgentMigrationCoordinatorStats migrationStats() {
        return migrationCoordinators.stream()
                .map(AgentMigrationCoordinator::stats)
                .reduce(AgentMigrationCoordinatorStats.empty(), AgentMigrationCoordinatorStats::plus);
    }

    private AgentMigrationExecutorStats migrationExecutorStats() {
        return migrationExecutors.stream()
                .map(AgentMigrationExecutor::stats)
                .reduce(AgentMigrationExecutorStats.empty(), AgentMigrationExecutorStats::plus);
    }

    private AgentMigrationRecoveryStats migrationRecoveryStats() {
        return migrationRecoveries.stream()
                .map(AgentMigrationRecoveryService::stats)
                .reduce(AgentMigrationRecoveryStats.empty(), AgentMigrationRecoveryStats::plus);
    }

    private AgentMigrationRecoverySchedulerStats migrationRecoverySchedulerStats() {
        return migrationRecoverySchedulers.stream()
                .map(AgentMigrationRecoveryScheduler::stats)
                .reduce(AgentMigrationRecoverySchedulerStats.empty(), AgentMigrationRecoverySchedulerStats::plus);
    }

    private AgentMigrationTaskRetentionStats migrationTaskRetentionStats() {
        return migrationTaskRetentions.stream()
                .map(AgentMigrationTaskRetentionService::stats)
                .reduce(AgentMigrationTaskRetentionStats.empty(), AgentMigrationTaskRetentionStats::plus);
    }

    private AgentMigrationTaskStoreStats migrationTaskStoreStats() {
        return migrationTaskStores.stream()
                .map(store -> store.stats(clock.instant()))
                .reduce(AgentMigrationTaskStoreStats.empty(), AgentMigrationTaskStoreStats::plus);
    }

    private EventOutboxStats outboxStats() {
        List<PendingVersionedEvent> pending = outbox.pending();
        int failedAttempts = pending.stream().mapToInt(PendingVersionedEvent::attempts).sum();
        long oldestAgeMillis = pending.stream()
                .map(PendingVersionedEvent::createdAt)
                .min(java.util.Comparator.naturalOrder())
                .map(createdAt -> Duration.between(createdAt, clock.instant()).toMillis())
                .orElse(0L);
        return new EventOutboxStats(pending.size(), failedAttempts, oldestAgeMillis);
    }

    private ClusterServiceStats clusterStats() {
        EnumMap<ServiceKind, Integer> counts = new EnumMap<>(ServiceKind.class);
        EnumMap<ServiceKind, Integer> drainingCounts = new EnumMap<>(ServiceKind.class);
        EnumMap<ServiceKind, Long> versions = new EnumMap<>(ServiceKind.class);
        EnumMap<ServiceKind, Map<String, Integer>> routeTagCounts = new EnumMap<>(ServiceKind.class);
        EnumMap<ServiceKind, Map<String, Integer>> deploymentGroupCounts = new EnumMap<>(ServiceKind.class);
        for (ServiceKind kind : ServiceKind.values()) {
            List<ServiceDescriptor> services = directory.list(kind);
            counts.put(kind, services.size());
            drainingCounts.put(kind, (int) services.stream().filter(ServiceDescriptor::draining).count());
            versions.put(kind, directory.version(kind));
            routeTagCounts.put(kind, metadataCounts(services, ServiceMetadata.ROUTE_TAG));
            deploymentGroupCounts.put(kind, metadataCounts(services, ServiceMetadata.DEPLOYMENT_GROUP));
        }
        return new ClusterServiceStats(counts, drainingCounts, versions, routeTagCounts, deploymentGroupCounts);
    }

    private static Map<String, Integer> metadataCounts(List<ServiceDescriptor> services, String key) {
        Map<String, Integer> counts = new HashMap<>();
        for (ServiceDescriptor service : services) {
            String value = service.metadata(key);
            if (value != null && !value.isBlank()) {
                counts.merge(value, 1, Integer::sum);
            }
        }
        return counts;
    }

    private RegistryLeaseHealthStats leaseStats() {
        long successfulHeartbeats = 0;
        long reRegistrations = 0;
        long failedRenewals = 0;
        for (RegistryLeaseRenewer renewer : leaseRenewers) {
            RegistryLeaseRenewalStats stats = renewer.stats();
            successfulHeartbeats += stats.successfulHeartbeats();
            reRegistrations += stats.reRegistrations();
            failedRenewals += stats.failedRenewals();
        }
        long expiredServices = leaseReapers.stream()
                .mapToLong(RegistryLeaseReaper::expiredServices)
                .sum();
        return new RegistryLeaseHealthStats(
                leaseRenewers.size(),
                successfulHeartbeats,
                reRegistrations,
                failedRenewals,
                leaseReapers.size(),
                expiredServices
        );
    }

    private RegistryHistoryHealthStats registryHistoryStats() {
        if (registryHistories.isEmpty()) {
            return RegistryHistoryHealthStats.empty();
        }
        long currentVersion = 0;
        long minReplayVersion = Long.MAX_VALUE;
        int retainedEvents = 0;
        int historyLimit = 0;
        long compactedReplayRequests = 0;
        for (RegistryHistoryView history : registryHistories) {
            RegistryHistoryStats stats = history.stats();
            currentVersion = Math.max(currentVersion, stats.currentVersion());
            minReplayVersion = Math.min(minReplayVersion, stats.minReplayVersion());
            retainedEvents += stats.retainedEvents();
            historyLimit += stats.historyLimit();
            compactedReplayRequests += stats.compactedReplayRequests();
        }
        return new RegistryHistoryHealthStats(
                registryHistories.size(),
                currentVersion,
                minReplayVersion == Long.MAX_VALUE ? 0 : minReplayVersion,
                retainedEvents,
                historyLimit,
                compactedReplayRequests
        );
    }

    private RemoteRegistryRecoveryHealthStats remoteRegistryRecoveryStats() {
        if (remoteRegistryRecoveries.isEmpty()) {
            return RemoteRegistryRecoveryHealthStats.empty();
        }
        long runs = 0;
        long succeededRuns = 0;
        long failedRuns = 0;
        long skippedRuns = 0;
        long recoveredKinds = 0;
        int inFlight = 0;
        for (RemoteRegistryRecoveryView recovery : remoteRegistryRecoveries) {
            RemoteRegistryRecoveryStats stats = recovery.stats();
            runs += stats.runs();
            succeededRuns += stats.succeededRuns();
            failedRuns += stats.failedRuns();
            skippedRuns += stats.skippedRuns();
            recoveredKinds += stats.recoveredKinds();
            if (stats.inFlight()) {
                inFlight++;
            }
        }
        return new RemoteRegistryRecoveryHealthStats(
                remoteRegistryRecoveries.size(),
                runs,
                succeededRuns,
                failedRuns,
                skippedRuns,
                recoveredKinds,
                inFlight
        );
    }

    private RegistrySubscriptionHealthStats registrySubscriptionStats() {
        if (registrySubscriptions.isEmpty()) {
            return RegistrySubscriptionHealthStats.empty();
        }
        int subscribedKinds = 0;
        int subscribers = 0;
        int references = 0;
        long subscribeRequests = 0;
        long unsubscribeRequests = 0;
        long cleanedSubscribers = 0;
        long expiredSubscriptions = 0;
        for (RegistrySubscriptionView subscription : registrySubscriptions) {
            RegistrySubscriptionStats stats = subscription.stats();
            subscribedKinds += stats.subscribedKinds();
            subscribers += stats.subscribers();
            references += stats.references();
            subscribeRequests += stats.subscribeRequests();
            unsubscribeRequests += stats.unsubscribeRequests();
            cleanedSubscribers += stats.cleanedSubscribers();
            expiredSubscriptions += stats.expiredSubscriptions();
        }
        return new RegistrySubscriptionHealthStats(
                registrySubscriptions.size(),
                subscribedKinds,
                subscribers,
                references,
                subscribeRequests,
                unsubscribeRequests,
                cleanedSubscribers,
                expiredSubscriptions
        );
    }

    private ServiceDescriptorPublisherHealthStats serviceDescriptorPublisherStats() {
        if (serviceDescriptorPublishers.isEmpty()) {
            return ServiceDescriptorPublisherHealthStats.empty();
        }
        int drainingPublishers = 0;
        long attempts = 0;
        long succeeded = 0;
        long failed = 0;
        for (ServiceDescriptorPublisher publisher : serviceDescriptorPublishers) {
            ServiceDescriptorPublisherStats stats = publisher.stats();
            if (stats.draining()) {
                drainingPublishers++;
            }
            attempts += stats.attempts();
            succeeded += stats.succeeded();
            failed += stats.failed();
        }
        return new ServiceDescriptorPublisherHealthStats(
                serviceDescriptorPublishers.size(),
                drainingPublishers,
                attempts,
                succeeded,
                failed
        );
    }

    private NetworkTransportHealthStats networkStats() {
        if (networkTransports.isEmpty()) {
            return NetworkTransportHealthStats.empty();
        }
        int activeConnections = 0;
        long connectionAttempts = 0;
        long connectionFailures = 0;
        long sentEnvelopes = 0;
        long failedWrites = 0;
        long receivedEnvelopes = 0;
        long inboundFailures = 0;
        for (NettyClusterTransport transport : networkTransports) {
            NettyTransportStats stats = transport.stats();
            activeConnections += stats.activeConnections();
            connectionAttempts += stats.connectionAttempts();
            connectionFailures += stats.connectionFailures();
            sentEnvelopes += stats.sentEnvelopes();
            failedWrites += stats.failedWrites();
            receivedEnvelopes += stats.receivedEnvelopes();
            inboundFailures += stats.inboundFailures();
        }
        return new NetworkTransportHealthStats(
                networkTransports.size(),
                activeConnections,
                connectionAttempts,
                connectionFailures,
                sentEnvelopes,
                failedWrites,
                receivedEnvelopes,
                inboundFailures
        );
    }

    private ConfigCacheHealthStats configStats() {
        if (configCaches.isEmpty()) {
            return ConfigCacheHealthStats.empty();
        }
        int ready = 0;
        int active = 0;
        int stale = 0;
        long minRevision = Long.MAX_VALUE;
        long maxRevision = 0;
        for (LocalGameConfigCache cache : configCaches) {
            if (cache.activeVersion().isPresent()) {
                active++;
            }
            if (cache.ready()) {
                ready++;
            }
            if (cache.stale()) {
                stale++;
            }
            long revision = cache.appliedEventRevision();
            minRevision = Math.min(minRevision, revision);
            maxRevision = Math.max(maxRevision, revision);
        }
        return new ConfigCacheHealthStats(configCaches.size(), active, ready, stale,
                minRevision == Long.MAX_VALUE ? 0 : minRevision, maxRevision);
    }

    private ConfigRecoveryHealthStats configRecoveryStats() {
        if (configRecoveries.isEmpty()) {
            return ConfigRecoveryHealthStats.empty();
        }
        int requested = 0;
        int skipped = 0;
        int succeeded = 0;
        int failed = 0;
        int inFlight = 0;
        for (GameConfigAutoRecovery recovery : configRecoveries) {
            GameConfigAutoRecoveryStats stats = recovery.stats();
            requested += stats.requested();
            skipped += stats.skippedWhileInFlight();
            succeeded += stats.succeeded();
            failed += stats.failed();
            if (stats.inFlight()) {
                inFlight++;
            }
        }
        return new ConfigRecoveryHealthStats(configRecoveries.size(), requested, skipped, succeeded, failed, inFlight);
    }

    private PlayerCommandAuditHealthStats commandAuditStats() {
        if (commandAudits.isEmpty()) {
            return PlayerCommandAuditHealthStats.empty();
        }
        long executed = 0;
        long failed = 0;
        long rejected = 0;
        long routedRemote = 0;
        long retained = 0;
        long dropped = 0;
        long maxElapsedMillis = 0;
        Map<Long, Long> configVersions = new HashMap<>();
        for (PlayerCommandAuditView audit : commandAudits) {
            PlayerCommandAuditStats stats = audit.stats();
            retained += stats.retained();
            dropped += stats.dropped();
            for (PlayerCommandAuditRecord record : audit.records()) {
                if (record.outcome() == PlayerCommandAuditOutcome.EXECUTED) {
                    executed++;
                } else if (record.outcome() == PlayerCommandAuditOutcome.FAILED) {
                    failed++;
                } else if (record.outcome() == PlayerCommandAuditOutcome.REJECTED) {
                    rejected++;
                } else if (record.outcome() == PlayerCommandAuditOutcome.ROUTED_REMOTE) {
                    routedRemote++;
                }
                maxElapsedMillis = Math.max(maxElapsedMillis, record.elapsed().toMillis());
                configVersions.merge(record.configVersion(), 1L, Long::sum);
            }
        }
        long total = executed + failed + rejected + routedRemote;
        return new PlayerCommandAuditHealthStats(total, retained, dropped, executed, failed, rejected, routedRemote,
                maxElapsedMillis, configVersions);
    }

    private EventSubscriptionHealthStats eventSubscriptionStats() {
        if (eventSubscriptions.isEmpty()) {
            return EventSubscriptionHealthStats.empty();
        }
        int registered = 0;
        int active = 0;
        long subscribeAttempts = 0;
        long subscribeFailures = 0;
        long replayAttempts = 0;
        long replayFailures = 0;
        long replayDelivered = 0;
        long replayUnavailableOwners = 0;
        long replayRepairRequests = 0;
        long replayRepairOwnerCount = 0;
        long replayRepairFailures = 0;
        long cursorFailures = 0;
        for (ClusterEventSubscriptionManager manager : eventSubscriptions) {
            ClusterEventSubscriptionStats stats = manager.stats();
            registered += stats.registered();
            active += stats.active();
            subscribeAttempts += stats.subscribeAttempts();
            subscribeFailures += stats.subscribeFailures();
            replayAttempts += stats.replayAttempts();
            replayFailures += stats.replayFailures();
            replayDelivered += stats.replayDelivered();
            replayUnavailableOwners += stats.replayUnavailableOwners();
            replayRepairRequests += stats.replayRepairRequests();
            replayRepairOwnerCount += stats.replayRepairOwnerCount();
            replayRepairFailures += stats.replayRepairFailures();
            cursorFailures += stats.cursorFailures();
        }
        return new EventSubscriptionHealthStats(eventSubscriptions.size(), registered, active, subscribeAttempts,
                subscribeFailures, replayAttempts, replayFailures, replayDelivered, replayUnavailableOwners,
                replayRepairRequests, replayRepairOwnerCount, replayRepairFailures, cursorFailures);
    }

    private EventCenterHealthStats eventCenterStats() {
        if (eventCenters.isEmpty()) {
            return EventCenterHealthStats.empty();
        }
        int topicCount = 0;
        int retainedEvents = 0;
        int retainedOwners = 0;
        int subscribers = 0;
        long publishedEvents = 0;
        long droppedEvents = 0;
        long deliveryFailures = 0;
        long expiredSubscriptions = 0;
        Map<String, EventCenterTopicAccumulator> topics = new HashMap<>();
        for (ClusterEventCenter center : eventCenters) {
            ClusterEventCenterStats stats = center.stats();
            topicCount += stats.topics().size();
            for (ClusterEventTopicStats topic : stats.topics().values()) {
                retainedEvents += topic.retainedEvents();
                retainedOwners += topic.retainedOwners();
                subscribers += topic.subscribers();
                publishedEvents += topic.publishedEvents();
                droppedEvents += topic.droppedEvents();
                deliveryFailures += topic.deliveryFailures();
                expiredSubscriptions += topic.expiredSubscriptions();
                topics.computeIfAbsent(topic.topic(), ignored -> new EventCenterTopicAccumulator(topic.topic()))
                        .add(topic);
            }
        }
        Map<String, EventCenterTopicHealthStats> topicStats = new HashMap<>();
        topics.forEach((topic, accumulator) -> topicStats.put(topic, accumulator.snapshot()));
        return new EventCenterHealthStats(eventCenters.size(), topicCount, retainedEvents, retainedOwners,
                subscribers, publishedEvents, droppedEvents, deliveryFailures, expiredSubscriptions, topicStats);
    }

    private ProfileInterestHealthStats profileInterestStats() {
        if (profileInterests.isEmpty()) {
            return ProfileInterestHealthStats.empty();
        }
        int watchedOwners = 0;
        int watchReferences = 0;
        long watchRequests = 0;
        long unwatchRequests = 0;
        long replayAttempts = 0;
        long replayFailures = 0;
        long repairRequests = 0;
        long repairFailures = 0;
        for (ProfileInterestView interest : profileInterests) {
            ProfileInterestStats stats = interest.stats();
            watchedOwners += stats.watchedOwners();
            watchReferences += stats.watchReferences();
            watchRequests += stats.watchRequests();
            unwatchRequests += stats.unwatchRequests();
            replayAttempts += stats.replayAttempts();
            replayFailures += stats.replayFailures();
            repairRequests += stats.repairRequests();
            repairFailures += stats.repairFailures();
        }
        return new ProfileInterestHealthStats(profileInterests.size(), watchedOwners, watchReferences, watchRequests, unwatchRequests,
                replayAttempts, replayFailures, repairRequests, repairFailures);
    }

    private ActorEventSubscriberHealthStats actorEventSubscriberStats() {
        if (actorEventSubscribers.isEmpty()) {
            return ActorEventSubscriberHealthStats.empty();
        }
        ActorEventSubscriberStats stats = actorEventSubscribers.stream()
                .map(ActorEventSubscriberView::stats)
                .reduce(ActorEventSubscriberStats.empty(), ActorEventSubscriberStats::plus);
        return ActorEventSubscriberHealthStats.from(actorEventSubscribers.size(), stats);
    }

    private OwnerActorEventSubscriptionHealthStats ownerActorEventSubscriptionStats() {
        if (ownerActorEventSubscriptions.isEmpty()) {
            return OwnerActorEventSubscriptionHealthStats.empty();
        }
        OwnerActorEventSubscriptionStats stats = ownerActorEventSubscriptions.stream()
                .map(OwnerActorEventSubscriptionView::stats)
                .reduce(OwnerActorEventSubscriptionStats.empty(), OwnerActorEventSubscriptionStats::plus);
        return OwnerActorEventSubscriptionHealthStats.from(ownerActorEventSubscriptions.size(), stats);
    }

    private ProfileRuntimeHealthStats profileRuntimeStats() {
        if (profileRuntimes.isEmpty()) {
            return ProfileRuntimeHealthStats.empty();
        }
        ProfileRuntimeStats stats = profileRuntimes.stream()
                .map(ProfileRuntimeView::stats)
                .reduce(ProfileRuntimeStats.empty(), ProfileRuntimeStats::plus);
        return ProfileRuntimeHealthStats.from(profileRuntimes.size(), stats);
    }

    private ChatRuntimeHealthStats chatRuntimeStats() {
        if (chatRuntimes.isEmpty()) {
            return ChatRuntimeHealthStats.empty();
        }
        ChatServiceStats stats = chatRuntimes.stream()
                .map(ChatRuntimeView::stats)
                .reduce(ChatServiceStats.empty(), ChatServiceStats::plus);
        return ChatRuntimeHealthStats.from(chatRuntimes.size(), stats);
    }

    private PlayerOutboundDeliveryHealthStats playerOutboundDeliveryStats() {
        if (playerOutboundDeliveries.isEmpty()) {
            return PlayerOutboundDeliveryHealthStats.empty();
        }
        PlayerOutboundDeliveryStats stats = playerOutboundDeliveries.stream()
                .map(PlayerOutboundDeliveryView::deliveryStats)
                .reduce(PlayerOutboundDeliveryStats.empty(), RuntimeHealthProbe::sumPlayerOutboundDeliveryStats);
        return PlayerOutboundDeliveryHealthStats.from(playerOutboundDeliveries.size(), stats);
    }

    private PlayerGatewayHealthStats playerGatewayStats() {
        if (playerGateways.isEmpty()) {
            return PlayerGatewayHealthStats.empty();
        }
        NettyPlayerGatewayStats stats = playerGateways.stream()
                .map(PlayerGatewayView::gatewayStats)
                .reduce(new NettyPlayerGatewayStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                        RuntimeHealthProbe::sumPlayerGatewayStats);
        return PlayerGatewayHealthStats.from(playerGateways.size(), stats);
    }

    private static NettyPlayerGatewayStats sumPlayerGatewayStats(
            NettyPlayerGatewayStats first,
            NettyPlayerGatewayStats second
    ) {
        return new NettyPlayerGatewayStats(
                first.acceptedLogins() + second.acceptedLogins(),
                first.failedLogins() + second.failedLogins(),
                first.authRejectedLogins() + second.authRejectedLogins(),
                first.duplicateRejectedLogins() + second.duplicateRejectedLogins(),
                first.kickedConnections() + second.kickedConnections(),
                first.acceptedCommands() + second.acceptedCommands(),
                first.rejectedCommands() + second.rejectedCommands(),
                first.rateLimitedCommands() + second.rateLimitedCommands(),
                first.acceptedHeartbeats() + second.acceptedHeartbeats(),
                first.rejectedHeartbeats() + second.rejectedHeartbeats(),
                first.rateLimitedHeartbeats() + second.rateLimitedHeartbeats(),
                first.acceptedAcks() + second.acceptedAcks(),
                first.rejectedAcks() + second.rejectedAcks(),
                first.slowClientClosures() + second.slowClientClosures(),
                first.invalidFrames() + second.invalidFrames(),
                first.disconnectedSessions() + second.disconnectedSessions(),
                first.idleTimeouts() + second.idleTimeouts()
        );
    }

    private static PlayerOutboundDeliveryStats sumPlayerOutboundDeliveryStats(
            PlayerOutboundDeliveryStats first,
            PlayerOutboundDeliveryStats second
    ) {
        return new PlayerOutboundDeliveryStats(
                first.activeConnections() + second.activeConnections(),
                first.offlinePlayers() + second.offlinePlayers(),
                first.pendingOfflineMessages() + second.pendingOfflineMessages(),
                first.pendingAckPlayers() + second.pendingAckPlayers(),
                first.pendingAckMessages() + second.pendingAckMessages(),
                Math.max(first.oldestPendingAckAgeMillis(), second.oldestPendingAckAgeMillis()),
                first.onlineDeliveries() + second.onlineDeliveries(),
                first.offlineQueuedDeliveries() + second.offlineQueuedDeliveries(),
                first.droppedDeliveries() + second.droppedDeliveries(),
                first.coalescedDeliveries() + second.coalescedDeliveries(),
                first.failedOnlineDeliveries() + second.failedOnlineDeliveries(),
                first.ackedDeliveries() + second.ackedDeliveries()
        );
    }

    private ShopRuntimeHealthStats shopRuntimeStats() {
        if (shopRuntimes.isEmpty()) {
            return ShopRuntimeHealthStats.empty();
        }
        ShopRuntimeStats stats = shopRuntimes.stream()
                .map(ShopRuntimeView::stats)
                .reduce(ShopRuntimeStats.empty(), ShopRuntimeStats::plus);
        return ShopRuntimeHealthStats.from(shopRuntimes.size(), stats);
    }

    private SceneRuntimeHealthStats sceneRuntimeStats() {
        if (sceneRuntimes.isEmpty()) {
            return SceneRuntimeHealthStats.empty();
        }
        SceneRuntimeStats stats = sceneRuntimes.stream()
                .map(SceneRuntimeView::stats)
                .reduce(SceneRuntimeStats.empty(), SceneRuntimeStats::plus);
        return SceneRuntimeHealthStats.from(sceneRuntimes.size(), stats);
    }

    private RuntimeHealthStatus status(
            int queuedTasks,
            int pendingEvents,
            PlayerCommandStats commandStats,
            PlayerBusinessResponseHealthStats businessResponseStats,
            AsyncShopPurchaseHealthStats asyncShopPurchaseStats,
            ConfigCacheHealthStats configStats,
            RegistryLeaseHealthStats leaseStats,
            RemoteRegistryRecoveryHealthStats remoteRegistryRecoveryStats,
            ServiceDescriptorPublisherHealthStats descriptorPublisherStats,
            NetworkTransportHealthStats networkStats,
            EventCenterHealthStats eventCenterStats,
            EventSubscriptionHealthStats eventSubscriptionStats,
            ActorEventSubscriberHealthStats actorEventSubscriberStats,
            OwnerActorEventSubscriptionHealthStats ownerActorEventSubscriptionStats,
            ProfileInterestHealthStats profileInterestStats,
            ProfileRuntimeHealthStats profileRuntimeStats,
            SceneRuntimeHealthStats sceneRuntimeStats,
            ShopRuntimeHealthStats shopRuntimeStats,
            PlayerAgentHealthStats playerAgentStats,
            AgentMigrationCoordinatorStats migrationStats,
            AgentMigrationExecutorStats migrationExecutorStats,
            AgentMigrationRecoveryStats migrationRecoveryStats,
            AgentMigrationRecoverySchedulerStats migrationRecoverySchedulerStats,
            AgentMigrationTaskRetentionStats migrationTaskRetentionStats,
            AgentMigrationTaskStoreStats migrationTaskStoreStats
    ) {
        if (!actors.isAccepting()) {
            return RuntimeHealthStatus.DOWN;
        }
        if (commandStats.drainingDispatchers() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (businessResponseStats.timedOutResponses() > 0
                || exceedsEnabledLimit(businessResponseStats.pendingResponses(),
                policy.maxPlayerBusinessPendingResponses())
                || exceedsEnabledLimit(businessResponseStats.oldestPendingAgeMillis(),
                policy.maxPlayerBusinessPendingResponseAgeMillis())) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (asyncShopPurchaseStats.rpcFailures() > 0
                || asyncShopPurchaseStats.lateCallbacks() > 0
                || asyncShopPurchaseStats.releaseFailures() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (configStats.cacheCount() > 0 && configStats.activeCaches() < configStats.cacheCount()) {
            return RuntimeHealthStatus.DOWN;
        }
        if (configStats.staleCaches() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (leaseStats.failedRenewals() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (remoteRegistryRecoveryStats.failedRuns() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (descriptorPublisherStats.failed() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (networkStats.connectionFailures() > 0 || networkStats.failedWrites() > 0 || networkStats.inboundFailures() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (eventCenterStats.deliveryFailures() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (eventSubscriptionStats.subscribeFailures() > 0
                || eventSubscriptionStats.replayFailures() > 0
                || eventSubscriptionStats.replayUnavailableOwners() > 0
                || eventSubscriptionStats.replayRepairFailures() > 0
                || eventSubscriptionStats.cursorFailures() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (actorEventSubscriberStats.rejectedEvents() > 0 || actorEventSubscriberStats.failedEvents() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (ownerActorEventSubscriptionStats.replayFailures() > 0
                || ownerActorEventSubscriptionStats.repairFailures() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (profileInterestStats.replayFailures() > 0 || profileInterestStats.repairFailures() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (profileRuntimeStats.remoteStale() > 0 || profileRuntimeStats.localFallbacks() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (exceedsEnabledLimit(sceneRuntimeStats.activeScenes(), policy.maxSceneActiveScenes())
                || exceedsEnabledLimit(sceneRuntimeStats.activePlayers(), policy.maxSceneActivePlayers())
                || exceedsEnabledLimit(sceneRuntimeStats.maxShardPlayers(), policy.maxSceneShardHotspotPlayers())
                || sceneRuntimeStats.missingLeaves() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (shopRuntimeStats.orderConflicts() > 0 || shopRuntimeStats.reservationReapFailures() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (playerAgentStats.autoSaveFailedRuns() > 0
                || playerAgentStats.autoSaveFailedSaves() > 0
                || playerAgentStats.drainFailedSaves() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (migrationStats.rollbackFailed() > 0
                || migrationExecutorStats.rejected() > 0
                || migrationExecutorStats.failed() > 0
                || migrationRecoveryStats.rollbackFailed() > 0
                || migrationRecoveryStats.executorRejected() > 0
                || migrationRecoverySchedulerStats.failedRuns() > 0
                || migrationTaskRetentionStats.failedRuns() > 0) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (migrationTaskStoreStats.oldestPendingAgeMillis() > policy.maxMigrationPendingTaskAgeMillis()) {
            return RuntimeHealthStatus.DEGRADED;
        }
        if (queuedTasks > policy.maxQueuedTasks() || pendingEvents > policy.maxPendingOutboxEvents()) {
            return RuntimeHealthStatus.DEGRADED;
        }
        return RuntimeHealthStatus.UP;
    }

    private static boolean exceedsEnabledLimit(int value, int limit) {
        return limit > 0 && value > limit;
    }

    private static boolean exceedsEnabledLimit(long value, long limit) {
        return limit > 0 && value > limit;
    }

    private static final class EventCenterTopicAccumulator {
        private final String topic;
        private int historyLimit;
        private int retainedEvents;
        private int retainedOwners;
        private int subscribers;
        private long publishedEvents;
        private long droppedEvents;
        private long deliveryFailures;
        private long expiredSubscriptions;
        private long minRetainedRevision = Long.MAX_VALUE;
        private long maxRetainedRevision;

        private EventCenterTopicAccumulator(String topic) {
            this.topic = topic;
        }

        private void add(ClusterEventTopicStats stats) {
            historyLimit += stats.historyLimit();
            retainedEvents += stats.retainedEvents();
            retainedOwners += stats.retainedOwners();
            subscribers += stats.subscribers();
            publishedEvents += stats.publishedEvents();
            droppedEvents += stats.droppedEvents();
            deliveryFailures += stats.deliveryFailures();
            expiredSubscriptions += stats.expiredSubscriptions();
            if (stats.minRetainedRevision() > 0) {
                minRetainedRevision = Math.min(minRetainedRevision, stats.minRetainedRevision());
            }
            maxRetainedRevision = Math.max(maxRetainedRevision, stats.maxRetainedRevision());
        }

        private EventCenterTopicHealthStats snapshot() {
            return new EventCenterTopicHealthStats(
                    topic,
                    historyLimit,
                    retainedEvents,
                    retainedOwners,
                    subscribers,
                    publishedEvents,
                    droppedEvents,
                    deliveryFailures,
                    expiredSubscriptions,
                    minRetainedRevision == Long.MAX_VALUE ? 0 : minRetainedRevision,
                    maxRetainedRevision
            );
        }
    }
}
