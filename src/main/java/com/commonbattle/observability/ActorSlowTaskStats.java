package com.commonbattle.observability;

import com.commonbattle.actor.ActorTaskCategory;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Actor 慢任务聚合统计。
 */
public record ActorSlowTaskStats(
        int retainedEntries,
        long recordedEntries,
        long droppedEntries,
        long maxElapsedMillis,
        String maxElapsedActorId,
        ActorTaskCategory maxElapsedCategory,
        Map<ActorTaskCategory, Long> byCategory
) {
    public ActorSlowTaskStats {
        Objects.requireNonNull(maxElapsedActorId, "maxElapsedActorId");
        Objects.requireNonNull(maxElapsedCategory, "maxElapsedCategory");
        Objects.requireNonNull(byCategory, "byCategory");
        if (retainedEntries < 0 || recordedEntries < 0 || droppedEntries < 0 || maxElapsedMillis < 0) {
            throw new IllegalArgumentException("slow task stats must not be negative");
        }
        EnumMap<ActorTaskCategory, Long> normalized = new EnumMap<>(ActorTaskCategory.class);
        for (ActorTaskCategory category : ActorTaskCategory.values()) {
            normalized.put(category, byCategory.getOrDefault(category, 0L));
        }
        byCategory = Map.copyOf(normalized);
    }

    public static ActorSlowTaskStats empty() {
        return new ActorSlowTaskStats(0, 0, 0, 0, "", ActorTaskCategory.DEFAULT, Map.of());
    }
}
