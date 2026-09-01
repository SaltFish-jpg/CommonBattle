package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystemStats;
import com.commonbattle.cluster.rpc.RpcGatewayStats;

import java.time.Instant;

/**
 * 单个游戏服进程的运行时健康快照。
 */
public record RuntimeHealthSnapshot(
        Instant timestamp,
        RuntimeHealthStatus status,
        ActorSystemStats actorSystem,
        RpcGatewayStats rpc,
        AgentLifecycleStats agents,
        EventOutboxStats outbox,
        ClusterServiceStats cluster
) {
}
