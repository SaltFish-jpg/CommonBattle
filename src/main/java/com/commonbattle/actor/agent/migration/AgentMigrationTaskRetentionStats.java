package com.commonbattle.actor.agent.migration;

/**
 * Agent 迁移任务保留清理统计快照。
 */
public record AgentMigrationTaskRetentionStats(long runs, long purgedTasks, long failedRuns) {
    public static AgentMigrationTaskRetentionStats empty() {
        return new AgentMigrationTaskRetentionStats(0, 0, 0);
    }

    public AgentMigrationTaskRetentionStats plus(AgentMigrationTaskRetentionStats other) {
        return new AgentMigrationTaskRetentionStats(
                runs + other.runs,
                purgedTasks + other.purgedTasks,
                failedRuns + other.failedRuns
        );
    }
}
