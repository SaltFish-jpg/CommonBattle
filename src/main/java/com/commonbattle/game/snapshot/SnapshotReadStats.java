package com.commonbattle.game.snapshot;

/**
 * 通用快照读取统计。
 * 用于统一观察本地命中、回源刷新、远端旧快照、远端缺失和本地降级读取。
 */
public record SnapshotReadStats(
        long requests,
        long localHits,
        long localStale,
        long localMisses,
        long refreshes,
        long remoteStale,
        long remoteMisses,
        long localFallbacks
) {
    public SnapshotReadStats {
        if (requests < 0 || localHits < 0 || localStale < 0 || localMisses < 0 || refreshes < 0
                || remoteStale < 0 || remoteMisses < 0 || localFallbacks < 0) {
            throw new IllegalArgumentException("snapshot read stats must not be negative");
        }
    }

    public static SnapshotReadStats empty() {
        return new SnapshotReadStats(0, 0, 0, 0, 0, 0, 0, 0);
    }

    public SnapshotReadStats plus(SnapshotReadStats other) {
        return new SnapshotReadStats(
                requests + other.requests,
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
