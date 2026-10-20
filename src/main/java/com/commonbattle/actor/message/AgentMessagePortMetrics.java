package com.commonbattle.actor.message;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

final class AgentMessagePortMetrics {
    private final LongAdder localTellAttempts = new LongAdder();
    private final LongAdder localTellAccepted = new LongAdder();
    private final LongAdder localTellRejected = new LongAdder();
    private final LongAdder localAskStarted = new LongAdder();
    private final LongAdder localAskSucceeded = new LongAdder();
    private final LongAdder localAskFailed = new LongAdder();
    private final LongAdder localAskTimedOut = new LongAdder();
    private final LongAdder localAskTargetDeliveryFailures = new LongAdder();
    private final LongAdder localAskCallbackDeliveryFailures = new LongAdder();
    private final LongAdder remoteCalls = new LongAdder();
    private final LongAdder remoteSucceeded = new LongAdder();
    private final LongAdder remoteFailed = new LongAdder();
    private final LongAdder remoteTimedOut = new LongAdder();
    private final LongAdder remoteLateResponses = new LongAdder();
    private final LongAdder remoteCallbackDeliveryFailures = new LongAdder();
    private final AtomicLong currentInFlightRemoteCalls = new AtomicLong();

    void localTellAttempted() {
        localTellAttempts.increment();
    }

    void localTellAccepted() {
        localTellAccepted.increment();
    }

    void localTellRejected() {
        localTellRejected.increment();
    }

    void localAskStarted() {
        localAskStarted.increment();
    }

    void localAskSucceeded() {
        localAskSucceeded.increment();
    }

    void localAskFailed() {
        localAskFailed.increment();
    }

    void localAskTimedOut() {
        localAskTimedOut.increment();
    }

    void localAskTargetDeliveryFailed() {
        localAskTargetDeliveryFailures.increment();
    }

    void localAskCallbackDeliveryFailed() {
        localAskCallbackDeliveryFailures.increment();
    }

    void remoteCallStarted() {
        remoteCalls.increment();
        currentInFlightRemoteCalls.incrementAndGet();
    }

    void remoteSucceeded() {
        remoteSucceeded.increment();
    }

    void remoteFailed() {
        remoteFailed.increment();
    }

    void remoteTimedOut() {
        remoteTimedOut.increment();
    }

    void remoteLateResponse() {
        remoteLateResponses.increment();
    }

    void remoteCallbackDeliveryFailed() {
        remoteCallbackDeliveryFailures.increment();
    }

    void remoteCallSettled() {
        currentInFlightRemoteCalls.updateAndGet(current -> Math.max(0, current - 1));
    }

    AgentMessagePortStats snapshot() {
        return new AgentMessagePortStats(
                localTellAttempts.sum(),
                localTellAccepted.sum(),
                localTellRejected.sum(),
                localAskStarted.sum(),
                localAskSucceeded.sum(),
                localAskFailed.sum(),
                localAskTimedOut.sum(),
                localAskTargetDeliveryFailures.sum(),
                localAskCallbackDeliveryFailures.sum(),
                remoteCalls.sum(),
                remoteSucceeded.sum(),
                remoteFailed.sum(),
                remoteTimedOut.sum(),
                remoteLateResponses.sum(),
                remoteCallbackDeliveryFailures.sum(),
                currentInFlightRemoteCalls.get()
        );
    }
}
