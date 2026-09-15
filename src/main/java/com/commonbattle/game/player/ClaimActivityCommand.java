package com.commonbattle.game.player;

import com.commonbattle.game.activity.ActivityClaimResult;

import java.util.Objects;

/**
 * 活动领奖示例业务命令。
 */
public record ClaimActivityCommand(String activityId) implements PlayerBusinessCommand<ActivityClaimResult> {
    public ClaimActivityCommand {
        Objects.requireNonNull(activityId, "activityId");
        if (activityId.isBlank()) {
            throw new IllegalArgumentException("activityId must not be blank");
        }
    }

    @Override
    public String operation() {
        return PlayerBusinessOperations.ACTIVITY_CLAIM;
    }

    @Override
    public ActivityClaimResult execute(PlayerGameExecution execution) {
        ActivityClaimResult result = execution.runtime().activityService().claim(
                execution.profile().activities(),
                execution.profile().bag(),
                execution.activityAccess(),
                activityId
        );
        execution.pushActivitySnapshot();
        execution.pushBagSnapshot();
        return result;
    }
}
