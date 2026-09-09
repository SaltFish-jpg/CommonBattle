package com.commonbattle.observability;

import com.commonbattle.game.player.PlayerBusinessResponseStats;

/**
 * 玩家业务响应等待链路的健康统计。
 */
public record PlayerBusinessResponseHealthStats(
        int hubCount,
        int pendingResponses,
        long submittedResponses,
        long completedResponses,
        long cancelledResponses,
        long timedOutResponses,
        long fallbackResponses,
        long sharedWaiters,
        long replayedResponses,
        int cachedResponses,
        long oldestPendingAgeMillis
) {
    public static PlayerBusinessResponseHealthStats empty() {
        return new PlayerBusinessResponseHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static PlayerBusinessResponseHealthStats from(int hubCount, PlayerBusinessResponseStats stats) {
        return new PlayerBusinessResponseHealthStats(
                hubCount,
                stats.pendingResponses(),
                stats.submittedResponses(),
                stats.completedResponses(),
                stats.cancelledResponses(),
                stats.timedOutResponses(),
                stats.fallbackResponses(),
                stats.sharedWaiters(),
                stats.replayedResponses(),
                stats.cachedResponses(),
                stats.oldestPendingAgeMillis()
        );
    }
}
