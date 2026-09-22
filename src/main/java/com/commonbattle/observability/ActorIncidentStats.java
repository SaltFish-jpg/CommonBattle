package com.commonbattle.observability;

import com.commonbattle.actor.ActorTaskCategory;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Actor 死信和毒消息的聚合统计。
 */
public record ActorIncidentStats(
        int retainedEntries,
        long recordedEntries,
        long deadLetters,
        long poisonMessages,
        long droppedEntries,
        Map<ActorIncidentKind, Long> byKind,
        Map<ActorTaskCategory, Long> byCategory,
        Map<String, Long> byReason
) {
    public ActorIncidentStats {
        Objects.requireNonNull(byKind, "byKind");
        Objects.requireNonNull(byCategory, "byCategory");
        Objects.requireNonNull(byReason, "byReason");
        if (retainedEntries < 0 || recordedEntries < 0 || deadLetters < 0 || poisonMessages < 0 || droppedEntries < 0) {
            throw new IllegalArgumentException("actor incident stats must not be negative");
        }
        byKind = Map.copyOf(byKind);
        byCategory = Map.copyOf(byCategory);
        byReason = Map.copyOf(byReason);
    }

    public static ActorIncidentStats empty() {
        EnumMap<ActorIncidentKind, Long> byKind = new EnumMap<>(ActorIncidentKind.class);
        for (ActorIncidentKind kind : ActorIncidentKind.values()) {
            byKind.put(kind, 0L);
        }
        EnumMap<ActorTaskCategory, Long> byCategory = new EnumMap<>(ActorTaskCategory.class);
        for (ActorTaskCategory category : ActorTaskCategory.values()) {
            byCategory.put(category, 0L);
        }
        return new ActorIncidentStats(0, 0, 0, 0, 0, byKind, byCategory, Map.of());
    }
}
