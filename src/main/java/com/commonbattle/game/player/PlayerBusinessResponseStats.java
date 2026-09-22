package com.commonbattle.game.player;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 玩家业务响应等待中心的运行统计。
 */
public record PlayerBusinessResponseStats(
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
    public PlayerBusinessResponseStats {
        failedResponsesByCode = sortedCopy(failedResponsesByCode);
        rejectedBusinessResultsByCode = sortedCopy(rejectedBusinessResultsByCode);
    }

    public static PlayerBusinessResponseStats empty() {
        return new PlayerBusinessResponseStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0, Map.of());
    }

    public PlayerBusinessResponseStats plus(PlayerBusinessResponseStats other) {
        return new PlayerBusinessResponseStats(
                pendingResponses + other.pendingResponses,
                submittedResponses + other.submittedResponses,
                completedResponses + other.completedResponses,
                cancelledResponses + other.cancelledResponses,
                timedOutResponses + other.timedOutResponses,
                fallbackResponses + other.fallbackResponses,
                sharedWaiters + other.sharedWaiters,
                replayedResponses + other.replayedResponses,
                cachedResponses + other.cachedResponses,
                Math.max(oldestPendingAgeMillis, other.oldestPendingAgeMillis),
                failedResponses + other.failedResponses,
                merge(failedResponsesByCode, other.failedResponsesByCode),
                rejectedBusinessResults + other.rejectedBusinessResults,
                merge(rejectedBusinessResultsByCode, other.rejectedBusinessResultsByCode)
        );
    }

    private static Map<String, Long> merge(Map<String, Long> first, Map<String, Long> second) {
        Map<String, Long> result = new HashMap<>(first);
        second.forEach((code, count) -> result.merge(code, count, Long::sum));
        return sortedCopy(result);
    }

    private static Map<String, Long> sortedCopy(Map<String, Long> values) {
        return Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNullElse(values, Map.of())));
    }
}
