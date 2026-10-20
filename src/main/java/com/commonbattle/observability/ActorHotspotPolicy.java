package com.commonbattle.observability;

/**
 * Actor 热点治理阈值。
 * 阈值只用于生成治理建议，真正限流、隔离或迁移仍由专门组件执行。
 */
public record ActorHotspotPolicy(
        int observeQueuedTasks,
        int throttleQueuedTasks,
        int migrationQueuedTasks,
        int throttleSlowTasks,
        int migrationSlowTasks,
        long throttleSlowTaskMillis,
        long migrationSlowTaskMillis,
        long throttleBatchYields,
        long migrationBatchYields
) {
    public ActorHotspotPolicy(
            int observeQueuedTasks,
            int throttleQueuedTasks,
            int migrationQueuedTasks,
            int throttleSlowTasks,
            int migrationSlowTasks,
            long throttleSlowTaskMillis,
            long migrationSlowTaskMillis
    ) {
        this(observeQueuedTasks, throttleQueuedTasks, migrationQueuedTasks, throttleSlowTasks, migrationSlowTasks,
                throttleSlowTaskMillis, migrationSlowTaskMillis, 2, 10);
    }

    public ActorHotspotPolicy {
        if (observeQueuedTasks < 0 || throttleQueuedTasks < 0 || migrationQueuedTasks < 0
                || throttleSlowTasks < 0 || migrationSlowTasks < 0
                || throttleSlowTaskMillis < 0 || migrationSlowTaskMillis < 0
                || throttleBatchYields < 0 || migrationBatchYields < 0) {
            throw new IllegalArgumentException("hotspot policy values must not be negative");
        }
        if (observeQueuedTasks > throttleQueuedTasks || throttleQueuedTasks > migrationQueuedTasks) {
            throw new IllegalArgumentException("queued thresholds must be ordered");
        }
        if (throttleSlowTasks > migrationSlowTasks) {
            throw new IllegalArgumentException("slow task count thresholds must be ordered");
        }
        if (throttleSlowTaskMillis > migrationSlowTaskMillis) {
            throw new IllegalArgumentException("slow task millis thresholds must be ordered");
        }
        if (throttleBatchYields > migrationBatchYields) {
            throw new IllegalArgumentException("batch yield thresholds must be ordered");
        }
    }

    public static ActorHotspotPolicy defaults() {
        return new ActorHotspotPolicy(1, 64, 256, 2, 5, 200, 1000, 2, 10);
    }
}
