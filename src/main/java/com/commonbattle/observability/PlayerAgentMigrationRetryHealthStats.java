package com.commonbattle.observability;

import com.commonbattle.game.player.PlayerAgentMigrationRetryStats;

/**
 * 玩家 Agent 源侧迁移重试聚合健康统计。
 */
public record PlayerAgentMigrationRetryHealthStats(
        int serviceCount,
        int pendingRetries,
        long retryableSourceFailures,
        long retryAttempts,
        long retrySubmitted,
        long retryCompleted
) {
    public static PlayerAgentMigrationRetryHealthStats empty() {
        return new PlayerAgentMigrationRetryHealthStats(0, 0, 0, 0, 0, 0);
    }

    public PlayerAgentMigrationRetryHealthStats plus(PlayerAgentMigrationRetryHealthStats other) {
        return new PlayerAgentMigrationRetryHealthStats(
                serviceCount + other.serviceCount,
                pendingRetries + other.pendingRetries,
                retryableSourceFailures + other.retryableSourceFailures,
                retryAttempts + other.retryAttempts,
                retrySubmitted + other.retrySubmitted,
                retryCompleted + other.retryCompleted
        );
    }

    public static PlayerAgentMigrationRetryHealthStats from(PlayerAgentMigrationRetryStats stats) {
        return new PlayerAgentMigrationRetryHealthStats(
                1,
                stats.pendingRetries(),
                stats.retryableSourceFailures(),
                stats.retryAttempts(),
                stats.retrySubmitted(),
                stats.retryCompleted()
        );
    }
}
