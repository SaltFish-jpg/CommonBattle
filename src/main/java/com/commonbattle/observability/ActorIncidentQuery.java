package com.commonbattle.observability;

import com.commonbattle.actor.ActorTaskCategory;

/**
 * Actor incident 查询条件。
 */
public record ActorIncidentQuery(
        ActorIncidentKind kind,
        String actorId,
        ActorTaskCategory category,
        String reason,
        int offset,
        int limit
) {
    public ActorIncidentQuery {
        actorId = actorId == null || actorId.isBlank() ? null : actorId.trim();
        reason = reason == null || reason.isBlank() ? null : reason.trim();
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
