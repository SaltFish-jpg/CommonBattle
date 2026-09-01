package com.commonbattle.actor;

/**
 * ActorSystem 运行时指标快照。
 */
public record ActorSystemStats(
        long submittedTasks,
        long completedTasks,
        long failedTasks,
        long rejectedTasks,
        int queuedTasks,
        int runningMailboxes
) {
}
