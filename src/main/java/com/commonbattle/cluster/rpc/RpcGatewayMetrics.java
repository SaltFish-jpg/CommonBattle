package com.commonbattle.cluster.rpc;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

final class RpcGatewayMetrics {
    private final LongAdder sentRequests = new LongAdder();
    private final LongAdder succeededRequests = new LongAdder();
    private final LongAdder failedRequests = new LongAdder();
    private final LongAdder timedOutRequests = new LongAdder();
    private final LongAdder rejectedRequests = new LongAdder();
    private final LongAdder slowRequests = new LongAdder();
    private final LongAdder forwardingFailedRequests = new LongAdder();
    private final LongAdder requestFailovers = new LongAdder();
    private final LongAdder replyWriteFailures = new LongAdder();
    private final LongAdder replyFailovers = new LongAdder();
    private final LongAdder undeliverableReplies = new LongAdder();
    private final LongAdder deliveryAckTimeouts = new LongAdder();
    private final LongAdder deliveryAckWriteFailures = new LongAdder();
    private final LongAdder idempotencyJoinedRequests = new LongAdder();
    private final AtomicInteger pendingRequests = new AtomicInteger();

    void sent() {
        sentRequests.increment();
        pendingRequests.incrementAndGet();
    }

    void success() {
        succeededRequests.increment();
        pendingRequests.decrementAndGet();
    }

    void failure() {
        failedRequests.increment();
        pendingRequests.decrementAndGet();
    }

    void timeout() {
        timedOutRequests.increment();
        pendingRequests.decrementAndGet();
    }

    void rejected() {
        rejectedRequests.increment();
    }

    void slow() {
        slowRequests.increment();
    }

    void forwardingFailure() {
        forwardingFailedRequests.increment();
    }

    void requestFailover() {
        requestFailovers.increment();
    }

    void replyWriteFailure() {
        replyWriteFailures.increment();
    }

    void replyFailover() {
        replyFailovers.increment();
    }

    void undeliverableReply() {
        undeliverableReplies.increment();
    }

    void deliveryAckTimeout() {
        deliveryAckTimeouts.increment();
    }

    void deliveryAckWriteFailure() {
        deliveryAckWriteFailures.increment();
    }

    void idempotencyJoined() {
        idempotencyJoinedRequests.increment();
    }

    RpcGatewayStats snapshot(int idempotencyCacheSize, int pendingDeliveryAcks, int idempotencyInFlightRequests) {
        return new RpcGatewayStats(
                sentRequests.sum(),
                succeededRequests.sum(),
                failedRequests.sum(),
                timedOutRequests.sum(),
                rejectedRequests.sum(),
                slowRequests.sum(),
                pendingRequests.get(),
                idempotencyCacheSize,
                forwardingFailedRequests.sum(),
                requestFailovers.sum(),
                replyWriteFailures.sum(),
                replyFailovers.sum(),
                undeliverableReplies.sum(),
                pendingDeliveryAcks,
                deliveryAckTimeouts.sum(),
                deliveryAckWriteFailures.sum(),
                idempotencyInFlightRequests,
                idempotencyJoinedRequests.sum()
        );
    }
}
