package com.commonbattle.observability;

import com.commonbattle.actor.ActorTaskCategory;

/**
 * Actor 慢任务查询条件。
 */
public record ActorSlowTaskQuery(
        String actorId,
        ActorTaskCategory category,
        long minElapsedMillis,
        int offset,
        int limit
) {
    public ActorSlowTaskQuery {
        actorId = actorId == null || actorId.isBlank() ? null : actorId;
        if (minElapsedMillis < 0 || offset < 0 || limit < 1) {
            throw new IllegalArgumentException("slow task query values must be valid");
        }
    }
}
