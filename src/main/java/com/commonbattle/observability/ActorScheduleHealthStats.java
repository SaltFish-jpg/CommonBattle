package com.commonbattle.observability;

import com.commonbattle.actor.ActorScheduleStats;

/**
 * Actor 业务定时调度健康统计。
 */
public record ActorScheduleHealthStats(
        int registryCount,
        int activeJobs,
        long scheduledJobs,
        long cancelledJobs,
        long deliveredTimerMessages,
        long rejectedTimerMessages
) {
    public ActorScheduleHealthStats {
        if (registryCount < 0
                || activeJobs < 0
                || scheduledJobs < 0
                || cancelledJobs < 0
                || deliveredTimerMessages < 0
                || rejectedTimerMessages < 0) {
            throw new IllegalArgumentException("actor schedule health stats must not be negative");
        }
    }

    public static ActorScheduleHealthStats empty() {
        return new ActorScheduleHealthStats(0, 0, 0, 0, 0, 0);
    }

    public static ActorScheduleHealthStats from(int registryCount, ActorScheduleStats stats) {
        return new ActorScheduleHealthStats(
                registryCount,
                stats.activeJobs(),
                stats.scheduledJobs(),
                stats.cancelledJobs(),
                stats.deliveredTimerMessages(),
                stats.rejectedTimerMessages()
        );
    }
}
