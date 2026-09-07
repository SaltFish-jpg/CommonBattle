package com.commonbattle.actor.agent.migration;

/**
 * Agent 迁移完成执行器统计快照。
 */
public record AgentMigrationExecutorStats(
        long submitted,
        long running,
        long completed,
        long failed,
        long rejected,
        long queuedTasks
) {
    public static AgentMigrationExecutorStats empty() {
        return new AgentMigrationExecutorStats(0, 0, 0, 0, 0, 0);
    }

    public AgentMigrationExecutorStats plus(AgentMigrationExecutorStats other) {
        return new AgentMigrationExecutorStats(
                submitted + other.submitted,
                running + other.running,
                completed + other.completed,
                failed + other.failed,
                rejected + other.rejected,
                queuedTasks + other.queuedTasks
        );
    }
}
