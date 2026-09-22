package com.commonbattle.observability;

import com.commonbattle.game.player.PlayerBusinessResponseStats;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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
        long oldestPendingAgeMillis,
        long failedResponses,
        Map<String, Long> failedResponsesByCode,
        long rejectedBusinessResults,
        Map<String, Long> rejectedBusinessResultsByCode
) {
    public PlayerBusinessResponseHealthStats {
        failedResponsesByCode = Collections.unmodifiableMap(
                new TreeMap<>(Objects.requireNonNullElse(failedResponsesByCode, Map.of()))
        );
        rejectedBusinessResultsByCode = Collections.unmodifiableMap(
                new TreeMap<>(Objects.requireNonNullElse(rejectedBusinessResultsByCode, Map.of()))
        );
    }

    public static PlayerBusinessResponseHealthStats empty() {
        return new PlayerBusinessResponseHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Map.of(), 0, Map.of());
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
                stats.oldestPendingAgeMillis(),
                stats.failedResponses(),
                stats.failedResponsesByCode(),
                stats.rejectedBusinessResults(),
                stats.rejectedBusinessResultsByCode()
        );
    }
}
