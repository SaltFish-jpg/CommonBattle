package com.commonbattle.observability;

import com.commonbattle.actor.ActorTaskCategory;

/**
 * Actor 热邮箱查询条件。
 * 运维接口用它筛选当前进程内正在积压的玩家、场景或业务 Actor 邮箱。
 */
public record ActorMailboxQuery(
        String group,
        String actorIdPrefix,
        ActorTaskCategory category,
        int minQueuedTasks,
        int offset,
        int limit
) {
    public ActorMailboxQuery {
        group = blankToNull(group);
        actorIdPrefix = blankToNull(actorIdPrefix);
        if (minQueuedTasks < 0) {
            throw new IllegalArgumentException("minQueuedTasks must not be negative");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
