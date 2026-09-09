package com.commonbattle.game.profile;

/**
 * 玩家基础资料运行时读取统计。
 */
public record ProfileRuntimeStats(
        long readRequests,
        long localHits,
        long localStale,
        long localMisses,
        long refreshes,
        long remoteStale,
        long remoteMisses,
        long localFallbacks
) {
    public static ProfileRuntimeStats empty() {
        return new ProfileRuntimeStats(0, 0, 0, 0, 0, 0, 0, 0);
    }

    public ProfileRuntimeStats plus(ProfileRuntimeStats other) {
        return new ProfileRuntimeStats(
                readRequests + other.readRequests,
                localHits + other.localHits,
                localStale + other.localStale,
                localMisses + other.localMisses,
                refreshes + other.refreshes,
                remoteStale + other.remoteStale,
                remoteMisses + other.remoteMisses,
                localFallbacks + other.localFallbacks
        );
    }
}
