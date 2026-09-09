package com.commonbattle.cluster.registry;

/**
 * 远程注册目录订阅恢复统计。
 */
public record RemoteRegistryRecoveryStats(
        long runs,
        long succeededRuns,
        long failedRuns,
        long skippedRuns,
        long recoveredKinds,
        boolean inFlight
) {
    public RemoteRegistryRecoveryStats {
        if (runs < 0 || succeededRuns < 0 || failedRuns < 0 || skippedRuns < 0 || recoveredKinds < 0) {
            throw new IllegalArgumentException("remote registry recovery stats must not be negative");
        }
    }

    public static RemoteRegistryRecoveryStats empty() {
        return new RemoteRegistryRecoveryStats(0, 0, 0, 0, 0, false);
    }
}
