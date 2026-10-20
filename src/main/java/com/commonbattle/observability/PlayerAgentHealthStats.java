package com.commonbattle.observability;

/**
 * 玩家 Agent 运行时健康统计。
 * 用于观察在线 owner 数、自动保存进度，以及停服排水是否完成。
 */
public record PlayerAgentHealthStats(
        int managerCount,
        int loadedAgents,
        long fencingConflicts,
        int autoSaveSchedulers,
        long autoSaveRuns,
        long autoSaveSubmitted,
        long autoSaveCompleted,
        long autoSaveFailedRuns,
        long autoSaveFailedSaves,
        long autoSavePendingAsyncBlockedSaves,
        int drainServices,
        int drainingServices,
        long drainSubmitted,
        long drainCompleted,
        long drainFailedSaves,
        long drainPendingAsyncBlockedSaves
) {
    public static PlayerAgentHealthStats empty() {
        return new PlayerAgentHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public long drainPending() {
        return Math.max(0, drainSubmitted - drainCompleted - drainFailedSaves);
    }
}
