package com.commonbattle.game.task;

import java.util.Objects;

/**
 * 玩家单个任务进度。
 */
public final class TaskProgress {
    private final String taskId;
    private int value;
    private boolean claimed;

    public TaskProgress(String taskId) {
        this.taskId = requireTaskId(taskId);
    }

    public String taskId() {
        return taskId;
    }

    public int value() {
        return value;
    }

    public boolean claimed() {
        return claimed;
    }

    public void increase(int delta) {
        if (delta <= 0) {
            throw new IllegalArgumentException("delta must be positive");
        }
        value += delta;
    }

    public void claim() {
        if (claimed) {
            throw new IllegalStateException("Task reward already claimed: " + taskId);
        }
        claimed = true;
    }

    public TaskProgressSnapshot snapshot() {
        return new TaskProgressSnapshot(value, claimed);
    }

    public void restore(TaskProgressSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        value = snapshot.value();
        claimed = snapshot.claimed();
    }

    private static String requireTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return taskId;
    }
}
