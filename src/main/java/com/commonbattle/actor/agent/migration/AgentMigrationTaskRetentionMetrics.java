package com.commonbattle.actor.agent.migration;

import java.util.concurrent.atomic.LongAdder;

final class AgentMigrationTaskRetentionMetrics {
    private final LongAdder runs = new LongAdder();
    private final LongAdder purgedTasks = new LongAdder();
    private final LongAdder failedRuns = new LongAdder();

    void run() {
        runs.increment();
    }

    void purgedTasks(int count) {
        purgedTasks.add(count);
    }

    void failedRun() {
        failedRuns.increment();
    }

    AgentMigrationTaskRetentionStats snapshot() {
        return new AgentMigrationTaskRetentionStats(runs.sum(), purgedTasks.sum(), failedRuns.sum());
    }
}
