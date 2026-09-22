package com.commonbattle.observability;

import com.commonbattle.actor.ActorTaskCategory;

import java.time.Instant;
import java.util.Objects;

/**
 * 单条 Actor 慢任务记录。
 */
public record ActorSlowTaskRecord(
        String actorId,
        ActorTaskCategory category,
        long elapsedMillis,
        long thresholdMillis,
        Instant at
) {
    public ActorSlowTaskRecord {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(at, "at");
        if (actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        if (elapsedMillis < 0 || thresholdMillis < 0) {
            throw new IllegalArgumentException("slow task millis must not be negative");
        }
    }
}
