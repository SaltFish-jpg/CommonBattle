package com.commonbattle.actor.rpc;

import com.commonbattle.actor.message.AgentDeliveryStatus;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

final class ActorRpcClientMetrics {
    private final LongAdder calls = new LongAdder();
    private final LongAdder succeededResponses = new LongAdder();
    private final LongAdder failedResponses = new LongAdder();
    private final LongAdder callbackDeliveryFailures = new LongAdder();
    private final EnumMap<AgentDeliveryStatus, LongAdder> failedResponsesByStatus =
            new EnumMap<>(AgentDeliveryStatus.class);

    ActorRpcClientMetrics() {
        for (AgentDeliveryStatus status : AgentDeliveryStatus.values()) {
            failedResponsesByStatus.put(status, new LongAdder());
        }
    }

    void callSubmitted() {
        calls.increment();
    }

    void succeededResponse() {
        succeededResponses.increment();
    }

    void failedResponse(AgentDeliveryStatus status) {
        failedResponses.increment();
        failedResponsesByStatus.get(status).increment();
    }

    void callbackDeliveryFailed() {
        callbackDeliveryFailures.increment();
    }

    ActorRpcClientStats snapshot() {
        EnumMap<AgentDeliveryStatus, Long> failures = new EnumMap<>(AgentDeliveryStatus.class);
        for (AgentDeliveryStatus status : AgentDeliveryStatus.values()) {
            failures.put(status, failedResponsesByStatus.get(status).sum());
        }
        return new ActorRpcClientStats(
                calls.sum(),
                succeededResponses.sum(),
                failedResponses.sum(),
                callbackDeliveryFailures.sum(),
                Map.copyOf(failures)
        );
    }
}
