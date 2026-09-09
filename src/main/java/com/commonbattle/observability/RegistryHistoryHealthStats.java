package com.commonbattle.observability;

/**
 * 注册中心事件历史健康统计。
 */
public record RegistryHistoryHealthStats(
        int viewCount,
        long currentVersion,
        long minReplayVersion,
        int retainedEvents,
        int historyLimit,
        long compactedReplayRequests
) {
    public RegistryHistoryHealthStats {
        if (viewCount < 0
                || currentVersion < 0
                || minReplayVersion < 0
                || retainedEvents < 0
                || historyLimit < 0
                || compactedReplayRequests < 0) {
            throw new IllegalArgumentException("registry history health stats must not be negative");
        }
    }

    public static RegistryHistoryHealthStats empty() {
        return new RegistryHistoryHealthStats(0, 0, 0, 0, 0, 0);
    }
}
