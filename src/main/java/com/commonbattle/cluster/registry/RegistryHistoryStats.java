package com.commonbattle.cluster.registry;

/**
 * 注册中心事件历史保留状态。
 */
public record RegistryHistoryStats(
        long currentVersion,
        long minReplayVersion,
        int retainedEvents,
        int historyLimit,
        long compactedReplayRequests
) {
    public RegistryHistoryStats {
        if (currentVersion < 0
                || minReplayVersion < 0
                || retainedEvents < 0
                || historyLimit < 0
                || compactedReplayRequests < 0) {
            throw new IllegalArgumentException("registry history stats must not be negative");
        }
    }

    public static RegistryHistoryStats empty() {
        return new RegistryHistoryStats(0, 0, 0, 0, 0);
    }
}
