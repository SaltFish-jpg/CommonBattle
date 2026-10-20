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
        Map<ActorTaskCategory, Integer> queuedTasksByCategory,
        long drainCycles,
        long drainProcessedTasks,
        int maxTasksPerDrain,
        long batchYields
) {
    public ActorMailboxStats(
            ActorRef actor,
            int queuedTasks,
            Map<ActorTaskCategory, Integer> queuedTasksByCategory
    ) {
        this(actor, queuedTasks, queuedTasksByCategory, 0, 0, 0, 0);
    }

    public ActorMailboxStats {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(queuedTasksByCategory, "queuedTasksByCategory");
        if (queuedTasks < 0 || drainCycles < 0 || drainProcessedTasks < 0
                || maxTasksPerDrain < 0 || batchYields < 0) {
            throw new IllegalArgumentException("mailbox stats must not be negative");
        }
        queuedTasksByCategory = Map.copyOf(queuedTasksByCategory);
    }
}
