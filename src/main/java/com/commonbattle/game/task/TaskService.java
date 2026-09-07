package com.commonbattle.game.task;

import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.PlayerBag;
import com.commonbattle.game.player.event.PlayerDomainEvent;

import java.util.Objects;

/**
 * 玩家任务服务。
 * 任务进度由玩家业务事件推进，领奖时再通过背包服务发放奖励。
 */
public final class TaskService {
    private final TaskCatalog catalog;
    private final BagService bagService;

    public TaskService(TaskCatalog catalog, BagService bagService) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.bagService = Objects.requireNonNull(bagService, "bagService");
    }

    public void onEvent(PlayerTasks tasks, PlayerDomainEvent event) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(event, "event");
        if (event.delta() <= 0 || event.replayed()) {
            return;
        }
        for (TaskDefinition definition : catalog.definitions()) {
            if (definition.progressRule().matches(event)) {
                tasks.progress(definition.taskId()).increase(event.delta());
            }
        }
    }

    public TaskClaimResult claim(PlayerTasks tasks, PlayerBag bag, String taskId) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(bag, "bag");
        TaskDefinition definition = catalog.require(taskId);
        TaskProgress progress = tasks.progress(taskId);
        if (progress.claimed()) {
            throw new IllegalStateException("Task reward already claimed: " + taskId);
        }
        if (progress.value() < definition.threshold()) {
            throw new IllegalStateException("Task reward is not ready: " + taskId);
        }
        // 任务领奖边界：先标记领取，再发奖励，避免派生事件或回调重入时重复领取。
        progress.claim();
        return new TaskClaimResult(taskId, progress.value(), bagService.grant(bag, definition.reward()));
    }
}
