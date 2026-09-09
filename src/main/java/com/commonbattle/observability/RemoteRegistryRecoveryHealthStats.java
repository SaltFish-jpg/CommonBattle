package com.commonbattle.observability;

/**
 * 远程注册目录恢复健康统计。
 */
public record RemoteRegistryRecoveryHealthStats(
        int schedulerCount,
        long runs,
        long succeededRuns,
        long failedRuns,
        long skippedRuns,
        long recoveredKinds,
        int inFlight
) {
    public RemoteRegistryRecoveryHealthStats {
        if (schedulerCount < 0
                || runs < 0
                || succeededRuns < 0
                || failedRuns < 0
                || skippedRuns < 0
                || recoveredKinds < 0
                || inFlight < 0) {
            throw new IllegalArgumentException("remote registry recovery health stats must not be negative");
        }
    }

    public static RemoteRegistryRecoveryHealthStats empty() {
        return new RemoteRegistryRecoveryHealthStats(0, 0, 0, 0, 0, 0, 0);
    }
}
