package com.commonbattle.observability;

import com.commonbattle.actor.message.AgentDeliveryStatus;
import com.commonbattle.actor.rpc.ActorRpcClientStats;

import java.util.EnumMap;
import java.util.Map;

/**
 * Actor RPC 调用模板健康统计。
 * 聚合进程内所有 ActorRpcClient，用于区分网关层失败和业务回包恢复层失败。
 */
public record ActorRpcHealthStats(
        int clientCount,
        long calls,
        long succeededResponses,
        long failedResponses,
        long callbackDeliveryFailures,
        Map<AgentDeliveryStatus, Long> failedResponsesByStatus
) {
    public static ActorRpcHealthStats empty() {
        return new ActorRpcHealthStats(0, 0, 0, 0, 0, Map.of());
    }

    public static ActorRpcHealthStats from(ActorRpcClientStats stats) {
        return new ActorRpcHealthStats(
                1,
                stats.calls(),
                stats.succeededResponses(),
                stats.failedResponses(),
                stats.callbackDeliveryFailures(),
                stats.failedResponsesByStatus()
        );
    }

    public ActorRpcHealthStats plus(ActorRpcHealthStats other) {
        EnumMap<AgentDeliveryStatus, Long> failures = new EnumMap<>(AgentDeliveryStatus.class);
        for (AgentDeliveryStatus status : AgentDeliveryStatus.values()) {
            failures.put(status, failedResponsesByStatus.getOrDefault(status, 0L)
                    + other.failedResponsesByStatus.getOrDefault(status, 0L));
        }
        return new ActorRpcHealthStats(
                clientCount + other.clientCount,
                calls + other.calls,
                succeededResponses + other.succeededResponses,
                failedResponses + other.failedResponses,
                callbackDeliveryFailures + other.callbackDeliveryFailures,
                Map.copyOf(failures)
        );
    }
}
