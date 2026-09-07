package com.commonbattle.game.task;

import com.commonbattle.game.bag.BagResult;

import java.util.Objects;

/**
 * 玩家任务领奖结果。
 */
public record TaskClaimResult(String taskId, int progress, BagResult reward) {
    public TaskClaimResult {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(reward, "reward");
    }
}
