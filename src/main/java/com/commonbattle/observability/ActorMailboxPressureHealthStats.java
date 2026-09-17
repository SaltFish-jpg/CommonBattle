package com.commonbattle.observability;

import com.commonbattle.actor.backpressure.ActorMailboxPressureStats;

import java.util.Map;

/**
 * Actor 邮箱压力准入控制的聚合健康统计。
 */
public record ActorMailboxPressureHealthStats(
        int controllerCount,
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
    public ActorMailboxPressureHealthStats {
        rejectedByTargetType = Map.copyOf(rejectedByTargetType);
        rejectedByActorGroup = Map.copyOf(rejectedByActorGroup);
        rejectedByReason = Map.copyOf(rejectedByReason);
    }

    public static ActorMailboxPressureHealthStats empty() {
        return from(0, ActorMailboxPressureStats.empty());
    }

    public static ActorMailboxPressureHealthStats from(int controllerCount, ActorMailboxPressureStats stats) {
        return new ActorMailboxPressureHealthStats(
                controllerCount,
                stats.admissions(),
                stats.accepted(),
                stats.delegateRejected(),
                stats.pressureRejected(),
                stats.targetPressureRejected(),
                stats.groupPressureRejected(),
                stats.rejectedByTargetType(),
                stats.rejectedByActorGroup(),
                stats.rejectedByReason()
        );
    }
}
