package com.commonbattle.observability;

import com.commonbattle.actor.ActorTaskCategory;

import java.time.Instant;
import java.util.Objects;

/**
 * Actor 死信或毒消息的轻量审计记录。
 * 记录只保留任务类别和错误摘要，不持有原始业务任务，避免运维视图延长业务对象生命周期。
 */
public record ActorIncidentRecord(
        ActorIncidentKind kind,
        String actorId,
        ActorTaskCategory category,
        String reason,
        String errorType,
        String message,
        Instant at
) {
    public ActorIncidentRecord {
        kind = Objects.requireNonNull(kind, "kind");
        actorId = Objects.requireNonNull(actorId, "actorId");
        category = category == null ? ActorTaskCategory.DEFAULT : category;
        reason = Objects.requireNonNullElse(reason, "").trim();
        errorType = Objects.requireNonNullElse(errorType, "").trim();
        message = Objects.requireNonNullElse(message, "").trim();
        at = Objects.requireNonNull(at, "at");
    }
}
