package com.commonbattle.actor.agent.migration;

/**
 * Agent 迁移恢复器统计快照。
 */
public record AgentMigrationRecoveryStats(
        long scans,
        long recoveredTasks,
        long targetAccepted,
        long targetRejected,
        long targetFailed,
        long targetRetries,
        long rollbackSucceeded,
        long rollbackFailed,
        long executorRejected
) {
    public static AgentMigrationRecoveryStats empty() {
        return new AgentMigrationRecoveryStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public AgentMigrationRecoveryStats plus(AgentMigrationRecoveryStats other) {
        return new AgentMigrationRecoveryStats(
                scans + other.scans,
                recoveredTasks + other.recoveredTasks,
                targetAccepted + other.targetAccepted,
                targetRejected + other.targetRejected,
                targetFailed + other.targetFailed,
                targetRetries + other.targetRetries,
                rollbackSucceeded + other.rollbackSucceeded,
                rollbackFailed + other.rollbackFailed,
                executorRejected + other.executorRejected
        );
    }
}
