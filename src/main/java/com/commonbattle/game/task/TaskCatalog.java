package com.commonbattle.game.task;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 玩家任务配置目录。
 */
public final class TaskCatalog {
    private final Map<String, TaskDefinition> tasks = new LinkedHashMap<>();

    public void register(TaskDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (tasks.putIfAbsent(definition.taskId(), definition) != null) {
            throw new IllegalArgumentException("Duplicate task: " + definition.taskId());
        }
    }

    public TaskDefinition require(String taskId) {
        Objects.requireNonNull(taskId, "taskId");
        TaskDefinition definition = tasks.get(taskId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown task: " + taskId);
        }
        return definition;
    }

    public Collection<TaskDefinition> definitions() {
        return java.util.List.copyOf(tasks.values());
    }
}
