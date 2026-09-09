package com.commonbattle.observability;

import com.commonbattle.game.profile.ProfileRuntimeStats;

/**
 * ProfileRuntime 聚合健康统计。
 */
public record ProfileRuntimeHealthStats(
        int runtimeCount,
        long readRequests,
        long localHits,
        long localStale,
        long localMisses,
        long refreshes,
        long remoteStale,
        long remoteMisses,
        long localFallbacks
) {
    public static ProfileRuntimeHealthStats empty() {
        return new ProfileRuntimeHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static ProfileRuntimeHealthStats from(int runtimeCount, ProfileRuntimeStats stats) {
        return new ProfileRuntimeHealthStats(
                runtimeCount,
                stats.readRequests(),
                stats.localHits(),
                stats.localStale(),
                stats.localMisses(),
                stats.refreshes(),
                stats.remoteStale(),
                stats.remoteMisses(),
                stats.localFallbacks()
        );
    }
}
