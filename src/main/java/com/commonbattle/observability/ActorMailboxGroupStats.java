package com.commonbattle.observability;

import com.commonbattle.actor.ActorTaskCategory;

import java.util.Map;
import java.util.Objects;

/**
 * 一组业务 Actor 邮箱的聚合排队视图。
 * 组名来自 ActorId 前缀，用于区分玩家、场景、聊天、商城和系统定时器等运行热点。
 */
public record ActorMailboxGroupStats(
        String group,
        int activeMailboxes,
        int queuedTasks,
        int largestMailboxQueuedTasks,
        String largestMailboxActorId,
        Map<ActorTaskCategory, Integer> queuedTasksByCategory
) {
    public ActorMailboxGroupStats {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(largestMailboxActorId, "largestMailboxActorId");
        Objects.requireNonNull(queuedTasksByCategory, "queuedTasksByCategory");
        if (group.isBlank()) {
            throw new IllegalArgumentException("group must not be blank");
        }
        if (activeMailboxes < 0 || queuedTasks < 0 || largestMailboxQueuedTasks < 0) {
            throw new IllegalArgumentException("mailbox stats must not be negative");
        }
        queuedTasksByCategory = Map.copyOf(queuedTasksByCategory);
    }
}
