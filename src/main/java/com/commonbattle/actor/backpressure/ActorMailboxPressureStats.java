package com.commonbattle.actor.backpressure;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Actor 邮箱压力准入控制的只读统计。
 * 维度只保留 target 类型、Actor 组和拒绝原因，避免把玩家 ID 或场景 ID 打进指标系统。
 */
public record ActorMailboxPressureStats(
        long admissions,
        long accepted,
        long delegateRejected,
        long pressureRejected,
        long targetPressureRejected,
        long groupPressureRejected,
        Map<String, Long> rejectedByTargetType,
        Map<String, Long> rejectedByActorGroup,
        Map<String, Long> rejectedByReason
) {
    public ActorMailboxPressureStats {
        Objects.requireNonNull(rejectedByTargetType, "rejectedByTargetType");
        Objects.requireNonNull(rejectedByActorGroup, "rejectedByActorGroup");
        Objects.requireNonNull(rejectedByReason, "rejectedByReason");
        rejectedByTargetType = Map.copyOf(rejectedByTargetType);
        rejectedByActorGroup = Map.copyOf(rejectedByActorGroup);
        rejectedByReason = Map.copyOf(rejectedByReason);
    }

    public static ActorMailboxPressureStats empty() {
        return new ActorMailboxPressureStats(0, 0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of());
    }

    public ActorMailboxPressureStats plus(ActorMailboxPressureStats other) {
        return new ActorMailboxPressureStats(
                admissions + other.admissions,
                accepted + other.accepted,
                delegateRejected + other.delegateRejected,
                pressureRejected + other.pressureRejected,
                targetPressureRejected + other.targetPressureRejected,
                groupPressureRejected + other.groupPressureRejected,
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
