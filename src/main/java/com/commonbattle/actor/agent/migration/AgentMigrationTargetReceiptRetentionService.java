package com.commonbattle.actor.agent.migration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * 目标迁入回执保留清理服务。
 * 回执只需要覆盖源服重试和恢复窗口，超过保留时间后可安全清理，避免幂等日志无限增长。
 */
public final class AgentMigrationTargetReceiptRetentionService {
    private final SerializedAgentMigrationTargetReceiptStore receiptStore;
    private final Clock clock;
    private final Duration retention;
    private final LongAdder runs = new LongAdder();
    private final LongAdder purgedReceipts = new LongAdder();
    private final LongAdder failedRuns = new LongAdder();

    public AgentMigrationTargetReceiptRetentionService(
            SerializedAgentMigrationTargetReceiptStore receiptStore,
            Clock clock,
            Duration retention
    ) {
        this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = positive(retention, "retention");
    }

    public int purge() {
        runs.increment();
        Instant cutoff = clock.instant().minus(retention);
        try {
            int purged = receiptStore.purgeBefore(cutoff);
            purgedReceipts.add(purged);
            return purged;
        } catch (RuntimeException e) {
            failedRuns.increment();
            throw e;
        }
    }

    public AgentMigrationTargetReceiptRetentionStats stats() {
        return new AgentMigrationTargetReceiptRetentionStats(
                runs.sum(),
                purgedReceipts.sum(),
                failedRuns.sum()
        );
    }

    private static Duration positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
