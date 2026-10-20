package com.commonbattle.observability;

import com.commonbattle.actor.message.AgentDeliveryStatus;
import com.commonbattle.game.agent.BusinessAgentMessageStats;
import com.commonbattle.game.agent.BusinessAgentLocalIdempotencyStats;

import java.util.EnumMap;
import java.util.Map;

/**
 * 业务 Agent 通信健康统计。
 * 聚合进程内业务消息端口，观察本服/跨服 Agent 请求和回调投递失败。
 */
public record BusinessAgentMessageHealthStats(
        int portCount,
        long submittedRequests,
        long localRequests,
        long remoteRequests,
        long rejectedRequests,
        long remoteSuccessResponses,
        long remoteFailureResponses,
        long remoteTimedOutResponses,
        long lateRemoteResponses,
        long callbackDeliveryFailures,
        Map<AgentDeliveryStatus, Long> callbackDeliveryFailuresByStatus,
        BusinessAgentLocalIdempotencyStats localIdempotency
) {
    public static BusinessAgentMessageHealthStats empty() {
        return new BusinessAgentMessageHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(),
                BusinessAgentLocalIdempotencyStats.empty());
    }

    public static BusinessAgentMessageHealthStats from(BusinessAgentMessageStats stats) {
        return new BusinessAgentMessageHealthStats(
                1,
                stats.submittedRequests(),
                stats.localRequests(),
                stats.remoteRequests(),
                stats.rejectedRequests(),
                stats.remoteSuccessResponses(),
                stats.remoteFailureResponses(),
                stats.remoteTimedOutResponses(),
                stats.lateRemoteResponses(),
                stats.callbackDeliveryFailures(),
                stats.callbackDeliveryFailuresByStatus(),
                stats.localIdempotency()
        );
    }

    public BusinessAgentMessageHealthStats plus(BusinessAgentMessageHealthStats other) {
        EnumMap<AgentDeliveryStatus, Long> failures = new EnumMap<>(AgentDeliveryStatus.class);
        for (AgentDeliveryStatus status : AgentDeliveryStatus.values()) {
            failures.put(status, callbackDeliveryFailuresByStatus.getOrDefault(status, 0L)
                    + other.callbackDeliveryFailuresByStatus.getOrDefault(status, 0L));
        }
        return new BusinessAgentMessageHealthStats(
                portCount + other.portCount,
                submittedRequests + other.submittedRequests,
                localRequests + other.localRequests,
                remoteRequests + other.remoteRequests,
                rejectedRequests + other.rejectedRequests,
                remoteSuccessResponses + other.remoteSuccessResponses,
                remoteFailureResponses + other.remoteFailureResponses,
                remoteTimedOutResponses + other.remoteTimedOutResponses,
                lateRemoteResponses + other.lateRemoteResponses,
                callbackDeliveryFailures + other.callbackDeliveryFailures,
                Map.copyOf(failures),
                plus(localIdempotency, other.localIdempotency)
        );
    }

    private static BusinessAgentLocalIdempotencyStats plus(
            BusinessAgentLocalIdempotencyStats left,
            BusinessAgentLocalIdempotencyStats right
    ) {
        return new BusinessAgentLocalIdempotencyStats(
                left.capacity() + right.capacity(),
                Math.max(left.ttlMillis(), right.ttlMillis()),
                left.size() + right.size(),
                left.startedRequests() + right.startedRequests(),
                left.joinedInFlightRequests() + right.joinedInFlightRequests(),
                left.completedCacheHits() + right.completedCacheHits(),
                left.expiredEntries() + right.expiredEntries(),
                left.evictedEntries() + right.evictedEntries(),
                left.completedSuccesses() + right.completedSuccesses(),
                left.completedFailures() + right.completedFailures(),
                left.abandonedInFlightRequests() + right.abandonedInFlightRequests()
        );
    }
}
