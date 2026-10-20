package com.commonbattle.observability;

import java.util.Map;
import java.util.Objects;

/**
 * 健康快照中的 Actor 热点动态准入统计。
 */
public record ActorHotspotAdmissionHealthStats(
        int controllerCount,
        long admissions,
        long accepted,
        long delegateRejected,
        long hotspotRejected,
        long throttleRejected,
        long migrationCandidateRejected,
        Map<String, Long> rejectedByTargetType,
        Map<String, Long> rejectedByActorGroup,
        Map<String, Long> rejectedByReason,
        Map<String, Long> rejectedByHotspotReason
) {
    public ActorHotspotAdmissionHealthStats(
            int controllerCount,
            long admissions,
            long accepted,
            long delegateRejected,
            long hotspotRejected,
            long throttleRejected,
            long migrationCandidateRejected,
            Map<String, Long> rejectedByTargetType,
            Map<String, Long> rejectedByActorGroup,
            Map<String, Long> rejectedByReason
    ) {
        this(controllerCount, admissions, accepted, delegateRejected, hotspotRejected, throttleRejected,
                migrationCandidateRejected, rejectedByTargetType, rejectedByActorGroup, rejectedByReason, Map.of());
    }

    public ActorHotspotAdmissionHealthStats {
        Objects.requireNonNull(rejectedByTargetType, "rejectedByTargetType");
        Objects.requireNonNull(rejectedByActorGroup, "rejectedByActorGroup");
        Objects.requireNonNull(rejectedByReason, "rejectedByReason");
        Objects.requireNonNull(rejectedByHotspotReason, "rejectedByHotspotReason");
        if (controllerCount < 0 || admissions < 0 || accepted < 0 || delegateRejected < 0
                || hotspotRejected < 0 || throttleRejected < 0 || migrationCandidateRejected < 0) {
            throw new IllegalArgumentException("hotspot admission health stats must not be negative");
        }
        rejectedByTargetType = Map.copyOf(rejectedByTargetType);
        rejectedByActorGroup = Map.copyOf(rejectedByActorGroup);
        rejectedByReason = Map.copyOf(rejectedByReason);
        rejectedByHotspotReason = Map.copyOf(rejectedByHotspotReason);
    }

    public static ActorHotspotAdmissionHealthStats empty() {
        return new ActorHotspotAdmissionHealthStats(0, 0, 0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(), Map.of());
    }
}
