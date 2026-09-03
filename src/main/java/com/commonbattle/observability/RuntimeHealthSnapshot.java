package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystemStats;
import com.commonbattle.cluster.rpc.RpcGatewayStats;
import com.commonbattle.game.session.PlayerCommandStats;

import java.time.Instant;

/**
 * 单个游戏服进程的运行时健康快照。
 */
public record RuntimeHealthSnapshot(
        Instant timestamp,
        RuntimeHealthStatus status,
        ActorSystemStats actorSystem,
        RpcGatewayStats rpc,
        RpcResilienceHealthStats rpcResilience,
        ActorRpcHealthStats actorRpc,
        PlayerCommandStats commands,
        AgentLifecycleStats agents,
        EventOutboxStats outbox,
        ClusterServiceStats cluster,
        RegistryLeaseHealthStats registryLeases,
        NetworkTransportHealthStats networkTransports,
        ConfigCacheHealthStats configCaches,
        ConfigRecoveryHealthStats configRecoveries,
        EventCenterHealthStats eventCenters,
        EventSubscriptionHealthStats eventSubscriptions,
        ProfileInterestHealthStats profileInterests,
        PlayerCommandAuditHealthStats commandAudits
) {
}
