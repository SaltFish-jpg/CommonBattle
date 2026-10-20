package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystemStats;
import com.commonbattle.actor.agent.migration.AgentMigrationCoordinatorStats;
import com.commonbattle.actor.agent.migration.AgentMigrationExecutorStats;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoveryStats;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoverySchedulerStats;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStoreStats;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskRetentionStats;
import com.commonbattle.cluster.rpc.RpcGatewayStats;
import com.commonbattle.game.session.PlayerCommandStats;
import com.commonbattle.game.player.PlayerMutationRepositoryStats;
import com.commonbattle.game.player.PlayerMutationReplaySchedulerStats;

import java.time.Instant;
import java.util.List;

/**
 * 单个游戏服进程的运行时健康快照。
 */
public record RuntimeHealthSnapshot(
        Instant timestamp,
        RuntimeHealthStatus status,
        ActorSystemStats actorSystem,
        ActorMailboxDiagnostics actorMailboxes,
        ActorMailboxPressureHealthStats actorMailboxPressures,
        ActorHotspotAdmissionHealthStats actorHotspotAdmissions,
        ActorIncidentHealthStats actorIncidents,
        ActorSlowTaskHealthStats actorSlowTasks,
        ActorScheduleHealthStats actorSchedules,
        RpcGatewayStats rpc,
        RpcResilienceHealthStats rpcResilience,
        RpcRouteHealthStats rpcRoutes,
        ActorRpcHealthStats actorRpc,
        AgentMessagePortHealthStats agentMessagePorts,
        BusinessAgentMessageHealthStats businessAgentMessages,
        BusinessAgentRpcEndpointHealthStats businessAgentRpcEndpoints,
        PlayerCommandStats commands,
        PlayerBusinessCommandCatalogHealthStats businessCommandCatalogs,
        PlayerBusinessCommandGatewayHealthStats businessCommandGateways,
        PlayerBusinessResponseHealthStats businessResponses,
        PlayerOutboundDeliveryHealthStats playerOutboundDeliveries,
        PlayerGatewayHealthStats playerGateways,
        AsyncShopPurchaseHealthStats asyncShopPurchases,
        AsyncBusinessCommandHealthStats asyncBusinessCommands,
        AgentLifecycleStats agents,
        PlayerAgentHealthStats playerAgents,
        AgentMigrationCoordinatorStats agentMigrations,
        PlayerAgentMigrationRetryHealthStats playerAgentMigrationRetries,
        AgentMigrationExecutorStats agentMigrationExecutors,
        AgentMigrationRecoveryStats agentMigrationRecoveries,
        AgentMigrationRecoverySchedulerStats agentMigrationRecoverySchedulers,
        AgentMigrationTaskRetentionStats agentMigrationTaskRetentions,
        AgentMigrationTaskStoreStats agentMigrationTaskStores,
        PlayerMutationRepositoryStats playerMutations,
        PlayerMutationReplaySchedulerStats playerMutationReplays,
        EventOutboxStats outbox,
        ClusterServiceStats cluster,
        RegistryLeaseHealthStats registryLeases,
        RegistryHistoryHealthStats registryHistory,
        RegistrySubscriptionHealthStats registrySubscriptions,
        RemoteRegistryRecoveryHealthStats remoteRegistryRecoveries,
        ServiceDescriptorPublisherHealthStats serviceDescriptorPublishers,
        NetworkTransportHealthStats networkTransports,
        ConfigCacheHealthStats configCaches,
        ConfigRecoveryHealthStats configRecoveries,
        EventCenterHealthStats eventCenters,
        EventSubscriptionHealthStats eventSubscriptions,
        ActorEventSubscriberHealthStats actorEventSubscribers,
        OwnerActorEventSubscriptionHealthStats ownerActorEventSubscriptions,
        OwnerEventRepairSchedulerHealthStats ownerEventRepairSchedulers,
        OwnerEventRepairDispatcherHealthStats ownerEventRepairDispatchers,
        OwnerRepairOpsAuditHealthStats ownerRepairOpsAudits,
        ProfileInterestHealthStats profileInterests,
        ProfileRuntimeHealthStats profileRuntimes,
        ChatRuntimeHealthStats chatRuntimes,
        SceneRuntimeHealthStats sceneRuntimes,
        ShopRuntimeHealthStats shopRuntimes,
        EventGatedCommandAuditHealthStats eventGatedCommandAudits,
        BagChangeAuditHealthStats bagChangeAudits,
        PlayerCommandAuditHealthStats commandAudits,
        List<RuntimeHealthIssue> issues
) {
    public RuntimeHealthSnapshot {
        issues = List.copyOf(issues);
    }
}
