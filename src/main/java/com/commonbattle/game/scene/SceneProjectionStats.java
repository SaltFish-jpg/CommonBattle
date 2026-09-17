package com.commonbattle.game.scene;

/**
 * Scene 侧 owner 事件投影质量统计。
 * 用于观察好友、联盟等外部 owner 数据在场景进程内的最终一致收敛情况。
 */
public record SceneProjectionStats(
        long receivedEvents,
        long appliedEvents,
        long duplicateEvents,
        long gapEvents,
        long repairRequests,
        long appliedSnapshots,
        long ignoredSnapshots,
        int staleViews
) {
    public SceneProjectionStats {
        if (receivedEvents < 0 || appliedEvents < 0 || duplicateEvents < 0 || gapEvents < 0
                || repairRequests < 0 || appliedSnapshots < 0 || ignoredSnapshots < 0 || staleViews < 0) {
            throw new IllegalArgumentException("scene projection stats must not be negative");
        }
    }

    public static SceneProjectionStats empty() {
        return new SceneProjectionStats(0, 0, 0, 0, 0, 0, 0, 0);
    }

    public SceneProjectionStats plus(SceneProjectionStats other) {
        return new SceneProjectionStats(
                receivedEvents + other.receivedEvents,
                appliedEvents + other.appliedEvents,
                duplicateEvents + other.duplicateEvents,
                gapEvents + other.gapEvents,
                repairRequests + other.repairRequests,
                appliedSnapshots + other.appliedSnapshots,
                ignoredSnapshots + other.ignoredSnapshots,
                staleViews + other.staleViews
        );
    }
}
