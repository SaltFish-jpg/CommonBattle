package com.commonbattle.game.task;

/**
 * 玩家任务进度快照。
 */
public record TaskProgressSnapshot(int value, boolean claimed) {
    public TaskProgressSnapshot {
        if (value < 0) {
            throw new IllegalArgumentException("value must not be negative");
        }
    }
}
