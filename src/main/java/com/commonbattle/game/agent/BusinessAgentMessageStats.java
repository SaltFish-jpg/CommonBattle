package com.commonbattle.game.agent;

import com.commonbattle.actor.message.AgentDeliveryStatus;

import java.util.Map;

/**
 * 业务 Agent 通信端口运行统计。
 * 用于观察本服/跨服业务 Agent 请求量，以及远端回包重新投递请求方邮箱失败的情况。
 */
public record BusinessAgentMessageStats(
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
    public BusinessAgentMessageStats(
            long submittedRequests,
            long localRequests,
            long remoteRequests,
            long rejectedRequests,
            long remoteSuccessResponses,
            long remoteFailureResponses,
            long remoteTimedOutResponses,
            long lateRemoteResponses,
            long callbackDeliveryFailures,
            Map<AgentDeliveryStatus, Long> callbackDeliveryFailuresByStatus
    ) {
        this(submittedRequests, localRequests, remoteRequests, rejectedRequests, remoteSuccessResponses,
                remoteFailureResponses, remoteTimedOutResponses, lateRemoteResponses, callbackDeliveryFailures,
                callbackDeliveryFailuresByStatus, BusinessAgentLocalIdempotencyStats.empty());
    }

    public static BusinessAgentMessageStats empty() {
        return new BusinessAgentMessageStats(0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());
    }

    public BusinessAgentMessageStats withLocalIdempotency(BusinessAgentLocalIdempotencyStats stats) {
        return new BusinessAgentMessageStats(
                submittedRequests,
                localRequests,
                remoteRequests,
                rejectedRequests,
                remoteSuccessResponses,
                remoteFailureResponses,
                remoteTimedOutResponses,
                lateRemoteResponses,
                callbackDeliveryFailures,
                callbackDeliveryFailuresByStatus,
                stats
        );
    }
}
