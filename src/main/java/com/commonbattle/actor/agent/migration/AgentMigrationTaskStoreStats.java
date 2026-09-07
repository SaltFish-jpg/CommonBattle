package com.commonbattle.actor.agent.migration;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * Agent 迁移任务存储统计快照。
 * 用于观察迁移日志积压、恢复卡点和租约占用，不参与迁移状态推进。
 */
public record AgentMigrationTaskStoreStats(
        long stores,
        long totalTasks,
        long preparedTasks,
        long movedTasks,
        long terminalTasks,
        long leasedPendingTasks,
        long oldestPendingAgeMillis
) {
    public static AgentMigrationTaskStoreStats empty() {
        return new AgentMigrationTaskStoreStats(0, 0, 0, 0, 0, 0, 0);
    }

    public static AgentMigrationTaskStoreStats from(long stores, Collection<AgentMigrationTask> tasks, Instant now) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(now, "now");
        long prepared = 0;
        long moved = 0;
        long terminal = 0;
        long leasedPending = 0;
        long oldestPendingAgeMillis = 0;
        for (AgentMigrationTask task : tasks) {
            if (task.status() == AgentMigrationTaskStatus.PREPARED) {
                prepared++;
            } else if (task.status() == AgentMigrationTaskStatus.MOVED) {
                moved++;
            } else {
                terminal++;
            }
            if (isPending(task)) {
                long ageMillis = Math.max(0, Duration.between(task.updatedAt(), now).toMillis());
                oldestPendingAgeMillis = Math.max(oldestPendingAgeMillis, ageMillis);
                if (!task.leaseAvailable(now)) {
                    leasedPending++;
                }
            }
        }
        return new AgentMigrationTaskStoreStats(stores, tasks.size(), prepared, moved, terminal,
                leasedPending, oldestPendingAgeMillis);
    }

    public AgentMigrationTaskStoreStats plus(AgentMigrationTaskStoreStats other) {
        return new AgentMigrationTaskStoreStats(
                stores + other.stores,
                totalTasks + other.totalTasks,
                preparedTasks + other.preparedTasks,
                movedTasks + other.movedTasks,
                terminalTasks + other.terminalTasks,
                leasedPendingTasks + other.leasedPendingTasks,
                Math.max(oldestPendingAgeMillis, other.oldestPendingAgeMillis)
        );
    }

    private static boolean isPending(AgentMigrationTask task) {
        return task.status() == AgentMigrationTaskStatus.PREPARED
                || task.status() == AgentMigrationTaskStatus.MOVED;
    }
}
