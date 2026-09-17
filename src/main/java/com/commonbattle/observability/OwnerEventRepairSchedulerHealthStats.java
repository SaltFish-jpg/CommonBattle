package com.commonbattle.observability;

import com.commonbattle.game.event.OwnerEventRepairSchedulerStats;

/**
 * owner 事件投影修复调度器聚合健康统计。
 */
public record OwnerEventRepairSchedulerHealthStats(
        int schedulerCount,
        int pendingOwners,
        int maxDefaultPriority,
        int highestPendingPriority,
        long repairRequests,
        long requestedOwners,
        long enqueuedOwners,
        long duplicateOwners,
        long dispatchRuns,
        long dispatchedOwners,
        long failedRuns,
        long skippedRuns,
        long backoffSkips,
        int maxConsecutiveFailures,
        boolean backoffActive,
        long maxBackoffRemainingMillis,
        int isolatedOwners,
        long isolatedOwnersTotal,
        long isolationSkips,
        long releasedIsolatedOwners,
        boolean singleOwnerProbeMode,
        boolean inFlight
) {
    public static OwnerEventRepairSchedulerHealthStats empty() {
        return new OwnerEventRepairSchedulerHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false,
                0, 0, 0, 0, 0, false, false);
    }

    public static OwnerEventRepairSchedulerHealthStats from(
            int schedulerCount,
            OwnerEventRepairSchedulerStats stats
    ) {
        return new OwnerEventRepairSchedulerHealthStats(
                schedulerCount,
                stats.pendingOwners(),
                stats.defaultPriority(),
                stats.highestPendingPriority(),
                stats.repairRequests(),
                stats.requestedOwners(),
                stats.enqueuedOwners(),
                stats.duplicateOwners(),
                stats.dispatchRuns(),
                stats.dispatchedOwners(),
                stats.failedRuns(),
                stats.skippedRuns(),
                stats.backoffSkips(),
                stats.consecutiveFailures(),
                stats.backoffActive(),
                stats.backoffRemainingMillis(),
                stats.isolatedOwners(),
                stats.isolatedOwnersTotal(),
                stats.isolationSkips(),
                stats.releasedIsolatedOwners(),
                stats.singleOwnerProbeMode(),
                stats.inFlight()
        );
    }
}
