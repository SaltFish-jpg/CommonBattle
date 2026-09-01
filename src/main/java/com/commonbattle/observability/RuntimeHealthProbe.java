package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcGatewayStats;
import com.commonbattle.game.event.PendingVersionedEvent;
import com.commonbattle.game.event.VersionedEventOutbox;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
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
            Collection<ClusterRpcGateway> rpcGateways,
            RuntimeHealthPolicy policy
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.lifecycles = Objects.requireNonNull(lifecycles, "lifecycles");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.rpcGateways = List.copyOf(Objects.requireNonNull(rpcGateways, "rpcGateways"));
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public RuntimeHealthSnapshot snapshot() {
        var actorStats = actors.stats();
        RpcGatewayStats rpcStats = rpcStats();
        AgentLifecycleStats agentStats = agentStats();
        EventOutboxStats outboxStats = outboxStats();
        ClusterServiceStats clusterStats = clusterStats();
        RuntimeHealthStatus status = status(actorStats.queuedTasks(), outboxStats.pendingEvents());
        return new RuntimeHealthSnapshot(clock.instant(), status, actorStats, rpcStats, agentStats, outboxStats, clusterStats);
    }

    private RpcGatewayStats rpcStats() {
        return rpcGateways.stream()
                .map(ClusterRpcGateway::stats)
                .reduce(RpcGatewayStats.empty(), RpcGatewayStats::plus);
    }

    private AgentLifecycleStats agentStats() {
        EnumMap<AgentLifecycleState, Integer> counts = new EnumMap<>(AgentLifecycleState.class);
        lifecycles.records().values().forEach(record ->
                counts.merge(record.state(), 1, Integer::sum));
        return new AgentLifecycleStats(counts);
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
        for (ServiceKind kind : ServiceKind.values()) {
            counts.put(kind, directory.list(kind).size());
        }
        return new ClusterServiceStats(counts);
    }

    private RuntimeHealthStatus status(int queuedTasks, int pendingEvents) {
        if (!actors.isAccepting()) {
            return RuntimeHealthStatus.DOWN;
        }
        if (queuedTasks > policy.maxQueuedTasks() || pendingEvents > policy.maxPendingOutboxEvents()) {
            return RuntimeHealthStatus.DEGRADED;
        }
        return RuntimeHealthStatus.UP;
    }
}
