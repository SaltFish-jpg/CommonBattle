package com.commonbattle.game.event;

/**
 * owner 事件投影修复调度统计。
 */
public record OwnerEventRepairSchedulerStats(
        int pendingOwners,
        int defaultPriority,
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
        int consecutiveFailures,
        boolean backoffActive,
        long backoffRemainingMillis,
        int isolatedOwners,
        long isolatedOwnersTotal,
        long isolationSkips,
        long releasedIsolatedOwners,
        boolean singleOwnerProbeMode,
        boolean inFlight
) {
    public static OwnerEventRepairSchedulerStats empty() {
        return new OwnerEventRepairSchedulerStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false,
                0, 0, 0, 0, 0, false, false);
    }

    public OwnerEventRepairSchedulerStats plus(OwnerEventRepairSchedulerStats other) {
        return new OwnerEventRepairSchedulerStats(
                pendingOwners + other.pendingOwners,
                Math.max(defaultPriority, other.defaultPriority),
                Math.max(highestPendingPriority, other.highestPendingPriority),
                repairRequests + other.repairRequests,
                requestedOwners + other.requestedOwners,
                enqueuedOwners + other.enqueuedOwners,
                duplicateOwners + other.duplicateOwners,
                dispatchRuns + other.dispatchRuns,
                dispatchedOwners + other.dispatchedOwners,
                failedRuns + other.failedRuns,
                skippedRuns + other.skippedRuns,
                backoffSkips + other.backoffSkips,
                Math.max(consecutiveFailures, other.consecutiveFailures),
                backoffActive || other.backoffActive,
                Math.max(backoffRemainingMillis, other.backoffRemainingMillis),
                isolatedOwners + other.isolatedOwners,
                isolatedOwnersTotal + other.isolatedOwnersTotal,
                isolationSkips + other.isolationSkips,
                releasedIsolatedOwners + other.releasedIsolatedOwners,
                singleOwnerProbeMode || other.singleOwnerProbeMode,
                inFlight || other.inFlight
        );
    }
}
