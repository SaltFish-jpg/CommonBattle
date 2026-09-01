package com.commonbattle.game.activity;

import com.commonbattle.game.bag.Reward;

import java.util.Objects;

/**
 * 活动配置。
 * threshold 表示领取奖励所需的进度，LOGIN 活动通常为 1，COUNTER 活动可表示击杀、参与次数等累计目标。
 */
public record ActivityDefinition(
        String activityId,
        ActivityType type,
        int threshold,
        Reward reward,
        ActivitySchedule schedule,
        ParticipationCondition participation
) {
    public ActivityDefinition(String activityId, ActivityType type, int threshold, Reward reward) {
        this(activityId, type, threshold, reward, ActivitySchedule.alwaysOpen(), ParticipationCondition.always());
    }

    public ActivityDefinition {
        Objects.requireNonNull(activityId, "activityId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reward, "reward");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(participation, "participation");
        if (activityId.isBlank()) {
            throw new IllegalArgumentException("Activity id must not be blank");
        }
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
    }
}
