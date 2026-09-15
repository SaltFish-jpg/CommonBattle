package com.commonbattle.actor;

import java.util.Map;

/**
 * ActorSystem 运行时指标快照。
 */
public record ActorSystemStats(
        long submittedTasks,
        long completedTasks,
        long failedTasks,
        long rejectedTasks,
        long droppedTasks,
        int queuedTasks,
        int runningMailboxes,
        int activeMailboxes,
        int largestMailboxQueuedTasks,
        String largestMailboxActorId,
        int peakQueuedTasks,
        int peakRunningMailboxes,
        long slowTasks,
        long slowestTaskMillis,
        String slowestTaskActorId,
        ActorTaskCategory slowestTaskCategory,
        Map<ActorTaskCategory, Integer> queuedTasksByCategory,
        Map<ActorTaskCategory, Long> rejectedTasksByCategory,
        Map<ActorTaskCategory, Long> droppedTasksByCategory
) {
}
