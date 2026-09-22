package com.commonbattle.actor.agent.migration;

/**
 * 目标迁入回执保留清理统计。
 */
public record AgentMigrationTargetReceiptRetentionStats(long runs, long purgedReceipts, long failedRuns) {
    public static AgentMigrationTargetReceiptRetentionStats empty() {
        return new AgentMigrationTargetReceiptRetentionStats(0, 0, 0);
    }

    public AgentMigrationTargetReceiptRetentionStats plus(AgentMigrationTargetReceiptRetentionStats other) {
        return new AgentMigrationTargetReceiptRetentionStats(
                runs + other.runs,
                purgedReceipts + other.purgedReceipts,
                failedRuns + other.failedRuns
        );
    }
}
