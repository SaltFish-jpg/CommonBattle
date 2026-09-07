package com.commonbattle.actor.agent.migration;

/**
 * Agent 迁移恢复调度器统计快照。
 */
public record AgentMigrationRecoverySchedulerStats(long runs, long claimedTasks, long failedRuns) {
    public static AgentMigrationRecoverySchedulerStats empty() {
        return new AgentMigrationRecoverySchedulerStats(0, 0, 0);
    }

    public AgentMigrationRecoverySchedulerStats plus(AgentMigrationRecoverySchedulerStats other) {
        return new AgentMigrationRecoverySchedulerStats(
                runs + other.runs,
                claimedTasks + other.claimedTasks,
                failedRuns + other.failedRuns
        );
    }
}
