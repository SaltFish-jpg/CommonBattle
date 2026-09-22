package com.commonbattle.observability;

import java.util.Objects;

/**
 * 单个 Actor 的热点治理候选。
 */
public record ActorHotspotCandidate(
        String actorId,
        String group,
        int queuedTasks,
        int recentSlowTasks,
        long maxSlowTaskMillis,
        ActorHotspotAction action
) {
    public ActorHotspotCandidate {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(action, "action");
        if (actorId.isBlank() || group.isBlank()) {
            throw new IllegalArgumentException("actor hotspot identifiers must not be blank");
        }
        if (queuedTasks < 0 || recentSlowTasks < 0 || maxSlowTaskMillis < 0) {
            throw new IllegalArgumentException("actor hotspot values must not be negative");
        }
    }
}
