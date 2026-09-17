package com.commonbattle.game.event;

/**
 * owner 事件投影共享修复分发器统计。
 */
public record OwnerEventRepairDispatcherStats(
        int registeredSchedulers,
        int dueSchedulers,
        int pendingSchedulers,
        int eligibleSchedulers,
        int highestPendingPriority,
        int maxDrainsPerTick,
        long tickIntervalMillis,
        long drainAttempts,
        long selectedSchedulers,
        long drainedOwners,
        long failedSchedulerRuns,
        long limitedRuns,
        long emptyRuns,
        long skippedRuns,
        boolean inFlight
) {
    public static OwnerEventRepairDispatcherStats empty() {
        return new OwnerEventRepairDispatcherStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
    }

    public OwnerEventRepairDispatcherStats plus(OwnerEventRepairDispatcherStats other) {
        return new OwnerEventRepairDispatcherStats(
                registeredSchedulers + other.registeredSchedulers,
                dueSchedulers + other.dueSchedulers,
                pendingSchedulers + other.pendingSchedulers,
                eligibleSchedulers + other.eligibleSchedulers,
                Math.max(highestPendingPriority, other.highestPendingPriority),
                Math.max(maxDrainsPerTick, other.maxDrainsPerTick),
                Math.max(tickIntervalMillis, other.tickIntervalMillis),
                drainAttempts + other.drainAttempts,
                selectedSchedulers + other.selectedSchedulers,
                drainedOwners + other.drainedOwners,
                failedSchedulerRuns + other.failedSchedulerRuns,
                limitedRuns + other.limitedRuns,
                emptyRuns + other.emptyRuns,
                skippedRuns + other.skippedRuns,
                inFlight || other.inFlight
        );
    }
}
