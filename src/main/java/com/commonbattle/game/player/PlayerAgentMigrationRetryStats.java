package com.commonbattle.game.player;

/**
 * 玩家 Agent 源侧迁移重试统计。
 */
public record PlayerAgentMigrationRetryStats(
        int pendingRetries,
        long retryableSourceFailures,
        long retryAttempts,
        long retrySubmitted,
        long retryCompleted
) {
}
