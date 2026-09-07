package com.commonbattle.actor.agent.migration;

import java.util.concurrent.atomic.LongAdder;

final class AgentMigrationCoordinatorMetrics {
    private final LongAdder initiated = new LongAdder();
    private final LongAdder sourceMoved = new LongAdder();
    private final LongAdder sourceMoveFailed = new LongAdder();
    private final LongAdder targetAccepted = new LongAdder();
    private final LongAdder targetRejected = new LongAdder();
    private final LongAdder targetFailed = new LongAdder();
    private final LongAdder targetRetries = new LongAdder();
    private final LongAdder completionRejected = new LongAdder();
    private final LongAdder rollbackSucceeded = new LongAdder();
    private final LongAdder rollbackFailed = new LongAdder();

    void initiated() {
        initiated.increment();
    }

    void sourceMoved() {
        sourceMoved.increment();
    }

    void sourceMoveFailed() {
        sourceMoveFailed.increment();
    }

    void targetAccepted() {
        targetAccepted.increment();
    }

    void targetRejected() {
        targetRejected.increment();
    }

    void targetFailed() {
        targetFailed.increment();
    }

    void targetRetry() {
        targetRetries.increment();
    }

    void completionRejected() {
        completionRejected.increment();
    }

    void rollbackSucceeded() {
        rollbackSucceeded.increment();
    }

    void rollbackFailed() {
        rollbackFailed.increment();
    }

    AgentMigrationCoordinatorStats snapshot() {
        return new AgentMigrationCoordinatorStats(
                initiated.sum(),
                sourceMoved.sum(),
                sourceMoveFailed.sum(),
                targetAccepted.sum(),
                targetRejected.sum(),
                targetFailed.sum(),
                targetRetries.sum(),
                completionRejected.sum(),
                rollbackSucceeded.sum(),
                rollbackFailed.sum()
        );
    }
}
