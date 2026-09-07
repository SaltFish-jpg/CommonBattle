package com.commonbattle.actor.agent.migration;

/**
 * Agent 迁移协调器统计快照。
 */
public record AgentMigrationCoordinatorStats(
        long initiated,
        long sourceMoved,
        long sourceMoveFailed,
        long targetAccepted,
        long targetRejected,
        long targetFailed,
        long targetRetries,
        long completionRejected,
        long rollbackSucceeded,
        long rollbackFailed
) {
    public static AgentMigrationCoordinatorStats empty() {
        return new AgentMigrationCoordinatorStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public AgentMigrationCoordinatorStats plus(AgentMigrationCoordinatorStats other) {
        return new AgentMigrationCoordinatorStats(
                initiated + other.initiated,
                sourceMoved + other.sourceMoved,
                sourceMoveFailed + other.sourceMoveFailed,
                targetAccepted + other.targetAccepted,
                targetRejected + other.targetRejected,
                targetFailed + other.targetFailed,
                targetRetries + other.targetRetries,
                completionRejected + other.completionRejected,
                rollbackSucceeded + other.rollbackSucceeded,
                rollbackFailed + other.rollbackFailed
        );
    }
}
