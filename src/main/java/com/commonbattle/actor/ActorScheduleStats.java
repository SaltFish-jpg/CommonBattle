package com.commonbattle.actor;

/**
 * Actor 业务定时调度统计。
 */
public record ActorScheduleStats(
        int activeJobs,
        long scheduledJobs,
        long cancelledJobs,
        long deliveredTimerMessages,
        long rejectedTimerMessages
) {
    public ActorScheduleStats {
        if (activeJobs < 0
                || scheduledJobs < 0
                || cancelledJobs < 0
                || deliveredTimerMessages < 0
                || rejectedTimerMessages < 0) {
            throw new IllegalArgumentException("schedule stats must not be negative");
        }
    }

    public static ActorScheduleStats empty() {
        return new ActorScheduleStats(0, 0, 0, 0, 0);
    }

    public ActorScheduleStats plus(ActorScheduleStats other) {
        return new ActorScheduleStats(
                activeJobs + other.activeJobs,
                scheduledJobs + other.scheduledJobs,
                cancelledJobs + other.cancelledJobs,
                deliveredTimerMessages + other.deliveredTimerMessages,
                rejectedTimerMessages + other.rejectedTimerMessages
        );
    }
}
