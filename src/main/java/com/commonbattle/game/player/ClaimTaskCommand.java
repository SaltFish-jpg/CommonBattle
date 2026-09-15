package com.commonbattle.game.player;

import com.commonbattle.game.task.TaskClaimResult;

import java.util.Objects;

/**
 * 玩家任务领奖命令。
 */
public record ClaimTaskCommand(String taskId) implements PlayerBusinessCommand<TaskClaimResult> {
    public ClaimTaskCommand {
        Objects.requireNonNull(taskId, "taskId");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
    }

    @Override
    public String operation() {
        return PlayerBusinessOperations.TASK_CLAIM;
    }

    @Override
    public TaskClaimResult execute(PlayerGameExecution execution) {
        TaskClaimResult result = execution.runtime().requireTaskService().claim(
                execution.profile().tasks(),
                execution.profile().bag(),
                taskId
        );
        execution.pushTaskSnapshot();
        execution.pushBagSnapshot();
        return result;
    }
}
