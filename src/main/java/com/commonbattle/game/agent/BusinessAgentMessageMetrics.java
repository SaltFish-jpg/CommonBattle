package com.commonbattle.game.agent;

import com.commonbattle.actor.message.AgentDeliveryStatus;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

final class BusinessAgentMessageMetrics {
    private final LongAdder submittedRequests = new LongAdder();
    private final LongAdder localRequests = new LongAdder();
    private final LongAdder remoteRequests = new LongAdder();
    private final LongAdder rejectedRequests = new LongAdder();
    private final LongAdder remoteSuccessResponses = new LongAdder();
    private final LongAdder remoteFailureResponses = new LongAdder();
    private final LongAdder remoteTimedOutResponses = new LongAdder();
    private final LongAdder lateRemoteResponses = new LongAdder();
    private final LongAdder callbackDeliveryFailures = new LongAdder();
    private final EnumMap<AgentDeliveryStatus, LongAdder> callbackDeliveryFailuresByStatus =
            new EnumMap<>(AgentDeliveryStatus.class);

    BusinessAgentMessageMetrics() {
        for (AgentDeliveryStatus status : AgentDeliveryStatus.values()) {
            callbackDeliveryFailuresByStatus.put(status, new LongAdder());
        }
    }

    void submittedRequest() {
        submittedRequests.increment();
    }

    void localRequest() {
        localRequests.increment();
    }

    void remoteRequest() {
        remoteRequests.increment();
    }

    void rejectedRequest() {
        rejectedRequests.increment();
    }

    void remoteSuccessResponse() {
        remoteSuccessResponses.increment();
    }

    void remoteFailureResponse() {
        remoteFailureResponses.increment();
    }

    void remoteTimedOutResponse() {
        remoteTimedOutResponses.increment();
    }

    void lateRemoteResponse() {
        lateRemoteResponses.increment();
    }

    void callbackDeliveryFailed(AgentDeliveryStatus status) {
        callbackDeliveryFailures.increment();
        callbackDeliveryFailuresByStatus.get(status).increment();
    }

    BusinessAgentMessageStats snapshot() {
        EnumMap<AgentDeliveryStatus, Long> failures = new EnumMap<>(AgentDeliveryStatus.class);
        for (AgentDeliveryStatus status : AgentDeliveryStatus.values()) {
            failures.put(status, callbackDeliveryFailuresByStatus.get(status).sum());
        }
        return new BusinessAgentMessageStats(
                submittedRequests.sum(),
                localRequests.sum(),
                remoteRequests.sum(),
                rejectedRequests.sum(),
                remoteSuccessResponses.sum(),
                remoteFailureResponses.sum(),
                remoteTimedOutResponses.sum(),
                lateRemoteResponses.sum(),
                callbackDeliveryFailures.sum(),
                Map.copyOf(failures)
        );
    }
}
