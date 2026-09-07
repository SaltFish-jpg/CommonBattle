package com.commonbattle.game.task;

import com.commonbattle.game.player.PlayerGameExecution;
import com.commonbattle.game.player.event.PlayerDomainEvent;
import com.commonbattle.game.player.event.PlayerEventHandler;

import java.util.Objects;

/**
 * 玩家业务事件到任务进度的桥接处理器。
 */
public final class TaskEventHandler implements PlayerEventHandler {
    private final TaskService taskService;

    public TaskEventHandler(TaskService taskService) {
        this.taskService = Objects.requireNonNull(taskService, "taskService");
    }

    @Override
    public void handle(PlayerGameExecution execution, PlayerDomainEvent event) {
        taskService.onEvent(execution.profile().tasks(), event);
    }
}
