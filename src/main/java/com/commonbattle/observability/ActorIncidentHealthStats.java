package com.commonbattle.observability;

import com.commonbattle.actor.ActorTaskCategory;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Actor incident 健康快照。
 */
public record ActorIncidentHealthStats(
        int viewCount,
        int retainedEntries,
        long recordedEntries,
        long deadLetters,
        long poisonMessages,
        long droppedEntries,
        Map<ActorIncidentKind, Long> byKind,
        Map<ActorTaskCategory, Long> byCategory,
        Map<String, Long> byReason
) {
    public ActorIncidentHealthStats {
        Objects.requireNonNull(byKind, "byKind");
        Objects.requireNonNull(byCategory, "byCategory");
        Objects.requireNonNull(byReason, "byReason");
        if (viewCount < 0 || retainedEntries < 0 || recordedEntries < 0 || deadLetters < 0
                || poisonMessages < 0 || droppedEntries < 0) {
            throw new IllegalArgumentException("actor incident health stats must not be negative");
        }
        byKind = Map.copyOf(byKind);
        byCategory = Map.copyOf(byCategory);
        byReason = Map.copyOf(byReason);
    }

    public static ActorIncidentHealthStats empty() {
        return from(0, ActorIncidentStats.empty());
    }

    public static ActorIncidentHealthStats from(int viewCount, ActorIncidentStats stats) {
        Objects.requireNonNull(stats, "stats");
        return new ActorIncidentHealthStats(
                viewCount,
                stats.retainedEntries(),
                stats.recordedEntries(),
                stats.deadLetters(),
                stats.poisonMessages(),
                stats.droppedEntries(),
                stats.byKind(),
                stats.byCategory(),
                stats.byReason()
        );
    }

    public ActorIncidentHealthStats plus(ActorIncidentHealthStats other) {
        Objects.requireNonNull(other, "other");
        return new ActorIncidentHealthStats(
                viewCount + other.viewCount,
                retainedEntries + other.retainedEntries,
                recordedEntries + other.recordedEntries,
                deadLetters + other.deadLetters,
                poisonMessages + other.poisonMessages,
                droppedEntries + other.droppedEntries,
                sumEnum(byKind, other.byKind, ActorIncidentKind.values()),
                sumEnum(byCategory, other.byCategory, ActorTaskCategory.values()),
                sumString(byReason, other.byReason)
        );
    }

    private static <E extends Enum<E>> Map<E, Long> sumEnum(Map<E, Long> left, Map<E, Long> right, E[] keys) {
        EnumMap<E, Long> result = new EnumMap<>(keys[0].getDeclaringClass());
        for (E key : keys) {
            result.put(key, left.getOrDefault(key, 0L) + right.getOrDefault(key, 0L));
        }
        return result;
    }

    private static Map<String, Long> sumString(Map<String, Long> left, Map<String, Long> right) {
        java.util.HashMap<String, Long> result = new java.util.HashMap<>(left);
        for (Map.Entry<String, Long> entry : right.entrySet()) {
            result.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        return result;
    }
}
