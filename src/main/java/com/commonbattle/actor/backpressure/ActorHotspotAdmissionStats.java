package com.commonbattle.actor.backpressure;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Actor 热点动态准入控制统计。
 * 只保留低基数维度，避免把玩家 ID 或场景 ID 写入指标系统。
 */
public record ActorHotspotAdmissionStats(
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
    public ActorHotspotAdmissionStats {
        Objects.requireNonNull(rejectedByTargetType, "rejectedByTargetType");
        Objects.requireNonNull(rejectedByActorGroup, "rejectedByActorGroup");
        Objects.requireNonNull(rejectedByReason, "rejectedByReason");
        rejectedByTargetType = Map.copyOf(rejectedByTargetType);
        rejectedByActorGroup = Map.copyOf(rejectedByActorGroup);
        rejectedByReason = Map.copyOf(rejectedByReason);
    }

    public static ActorHotspotAdmissionStats empty() {
        return new ActorHotspotAdmissionStats(0, 0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of());
    }

    public ActorHotspotAdmissionStats plus(ActorHotspotAdmissionStats other) {
        return new ActorHotspotAdmissionStats(
                admissions + other.admissions,
                accepted + other.accepted,
                delegateRejected + other.delegateRejected,
                hotspotRejected + other.hotspotRejected,
                throttleRejected + other.throttleRejected,
                migrationCandidateRejected + other.migrationCandidateRejected,
                sum(rejectedByTargetType, other.rejectedByTargetType),
                sum(rejectedByActorGroup, other.rejectedByActorGroup),
                sum(rejectedByReason, other.rejectedByReason)
        );
    }

    private static Map<String, Long> sum(Map<String, Long> first, Map<String, Long> second) {
        Map<String, Long> result = new HashMap<>(first);
        second.forEach((key, value) -> result.merge(key, value, Long::sum));
        return Map.copyOf(result);
    }
}
