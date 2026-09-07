package com.commonbattle.game.task;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 玩家任务状态。
 * 该对象只应在玩家 Agent 邮箱内修改。
 */
public final class PlayerTasks {
    private final Map<String, TaskProgress> progress = new HashMap<>();

    public TaskProgress progress(String taskId) {
        return progress.computeIfAbsent(taskId, TaskProgress::new);
    }

    public PlayerTasksSnapshot snapshot() {
        Map<String, TaskProgressSnapshot> snapshots = new HashMap<>();
        progress.forEach((taskId, taskProgress) -> snapshots.put(taskId, taskProgress.snapshot()));
        return new PlayerTasksSnapshot(snapshots);
    }

    public void restore(PlayerTasksSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        progress.clear();
        snapshot.progress().forEach((taskId, saved) -> {
            TaskProgress restored = new TaskProgress(taskId);
            restored.restore(saved);
            progress.put(taskId, restored);
        });
    }
}
