package com.commonbattle.game.profile;

/**
 * Profile 动态关注订阅统计。
 */
public record ProfileInterestStats(
        int watchedOwners,
        long watchRequests,
        long unwatchRequests,
        long replayAttempts,
        long replayFailures,
        long repairRequests,
        long repairFailures
) {
}
