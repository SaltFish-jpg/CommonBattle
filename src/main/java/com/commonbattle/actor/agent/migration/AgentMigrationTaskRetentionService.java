package com.commonbattle.actor.agent.migration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Agent 迁移任务保留清理服务。
 * 定时器、运维命令或启动恢复流程可调用 purge 清理已进入终态且超过保留时间的迁移日志。
 */
public final class AgentMigrationTaskRetentionService {
    private final AgentMigrationTaskStore taskStore;
    private final Clock clock;
    private final Duration retention;
    private final AgentMigrationTaskRetentionMetrics metrics = new AgentMigrationTaskRetentionMetrics();

    public AgentMigrationTaskRetentionService(
            AgentMigrationTaskStore taskStore,
            Clock clock,
            Duration retention
    ) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive");
        }
    }

    public int purge() {
        metrics.run();
        Instant cutoff = clock.instant().minus(retention);
        try {
            int purged = taskStore.purgeTerminalTasksBefore(cutoff);
            metrics.purgedTasks(purged);
            return purged;
        } catch (RuntimeException e) {
            metrics.failedRun();
            throw e;
        }
    }

    public AgentMigrationTaskRetentionStats stats() {
        return metrics.snapshot();
    }
}
