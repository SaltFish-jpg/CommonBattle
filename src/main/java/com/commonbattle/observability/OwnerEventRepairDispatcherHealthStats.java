package com.commonbattle.observability;

import com.commonbattle.game.event.OwnerEventRepairDispatcherStats;

/**
 * owner 事件投影共享修复分发器聚合健康统计。
 */
public record OwnerEventRepairDispatcherHealthStats(
        int dispatcherCount,
        int registeredSchedulers,
        int dueSchedulers,
        int pendingSchedulers,
        int eligibleSchedulers,
        int highestPendingPriority,
        int maxDrainsPerTick,
        long maxTickIntervalMillis,
        long drainAttempts,
        long selectedSchedulers,
        long drainedOwners,
        long failedSchedulerRuns,
        long limitedRuns,
        long emptyRuns,
        long skippedRuns,
        boolean inFlight
) {
    public static OwnerEventRepairDispatcherHealthStats empty() {
        return new OwnerEventRepairDispatcherHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
    }

    public static OwnerEventRepairDispatcherHealthStats from(
            int dispatcherCount,
            OwnerEventRepairDispatcherStats stats
    ) {
        return new OwnerEventRepairDispatcherHealthStats(
                dispatcherCount,
                stats.registeredSchedulers(),
                stats.dueSchedulers(),
                stats.pendingSchedulers(),
                stats.eligibleSchedulers(),
                stats.highestPendingPriority(),
                stats.maxDrainsPerTick(),
                stats.tickIntervalMillis(),
                stats.drainAttempts(),
                stats.selectedSchedulers(),
                stats.drainedOwners(),
                stats.failedSchedulerRuns(),
                stats.limitedRuns(),
                stats.emptyRuns(),
                stats.skippedRuns(),
                stats.inFlight()
        );
    }
}
