package com.commonbattle.actor.agent.migration;

import java.util.concurrent.atomic.LongAdder;

final class AgentMigrationRecoveryMetrics {
    private final LongAdder scans = new LongAdder();
    private final LongAdder recoveredTasks = new LongAdder();
    private final LongAdder targetAccepted = new LongAdder();
    private final LongAdder targetRejected = new LongAdder();
    private final LongAdder targetFailed = new LongAdder();
    private final LongAdder targetRetries = new LongAdder();
    private final LongAdder rollbackSucceeded = new LongAdder();
    private final LongAdder rollbackFailed = new LongAdder();
    private final LongAdder executorRejected = new LongAdder();

    void scan() {
        scans.increment();
    }

    void recoveredTask() {
        recoveredTasks.increment();
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

    void rollbackSucceeded() {
        rollbackSucceeded.increment();
    }

    void rollbackFailed() {
        rollbackFailed.increment();
    }

    void executorRejected() {
        executorRejected.increment();
    }

    AgentMigrationRecoveryStats snapshot() {
        return new AgentMigrationRecoveryStats(
                scans.sum(),
                recoveredTasks.sum(),
                targetAccepted.sum(),
                targetRejected.sum(),
                targetFailed.sum(),
                targetRetries.sum(),
                rollbackSucceeded.sum(),
                rollbackFailed.sum(),
                executorRejected.sum()
        );
    }
}
