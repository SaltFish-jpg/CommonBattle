package com.commonbattle.observability;

/**
 * Profile 动态关注订阅健康统计。
 */
public record ProfileInterestHealthStats(
        int subscriptionCount,
        int watchedOwners,
        int watchReferences,
        long watchRequests,
        long unwatchRequests,
        long replayAttempts,
        long replayFailures,
        long repairRequests,
        long repairFailures
) {
    public static ProfileInterestHealthStats empty() {
        return new ProfileInterestHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
