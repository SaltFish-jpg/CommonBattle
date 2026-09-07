package com.commonbattle.game.task;

import java.util.Map;

/**
 * 玩家任务状态快照。
 */
public record PlayerTasksSnapshot(Map<String, TaskProgressSnapshot> progress) {
    public PlayerTasksSnapshot {
        progress = Map.copyOf(progress);
    }

    public static PlayerTasksSnapshot empty() {
        return new PlayerTasksSnapshot(Map.of());
    }
}
