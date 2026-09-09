package com.commonbattle.game.player;

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
        long oldestPendingAgeMillis
) {
    public static PlayerBusinessResponseStats empty() {
        return new PlayerBusinessResponseStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
                Math.max(oldestPendingAgeMillis, other.oldestPendingAgeMillis)
        );
    }
}
