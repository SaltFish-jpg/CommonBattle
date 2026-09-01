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

    RpcGatewayStats snapshot(int idempotencyCacheSize) {
        return new RpcGatewayStats(
                sentRequests.sum(),
                succeededRequests.sum(),
                failedRequests.sum(),
                timedOutRequests.sum(),
                rejectedRequests.sum(),
                slowRequests.sum(),
                pendingRequests.get(),
                idempotencyCacheSize
        );
    }
}
