package com.commonbattle.actor;

import java.util.Map;
import java.util.Objects;

/**
 * 单个 Actor 邮箱的只读排队快照。
 * 运维和治理组件使用它定位热点玩家、场景或系统 Actor，不应通过它反向修改邮箱状态。
 */
public record ActorMailboxStats(
        ActorRef actor,
        int queuedTasks,
        Map<ActorTaskCategory, Integer> queuedTasksByCategory
) {
    public ActorMailboxStats {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(queuedTasksByCategory, "queuedTasksByCategory");
        if (queuedTasks < 0) {
            throw new IllegalArgumentException("queuedTasks must not be negative");
        }
        queuedTasksByCategory = Map.copyOf(queuedTasksByCategory);
    }
}
