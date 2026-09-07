package com.commonbattle.game.player;

import java.util.Objects;

/**
 * 增加玩家活动进度的示例业务命令。
 */
public record ActivityProgressCommand(String activityId, int delta) implements PlayerBusinessCommand<PlayerBusinessAck> {
    public ActivityProgressCommand {
        Objects.requireNonNull(activityId, "activityId");
        if (activityId.isBlank()) {
            throw new IllegalArgumentException("activityId must not be blank");
        }
        if (delta <= 0) {
            throw new IllegalArgumentException("delta must be positive");
        }
    }

    @Override
    public String operation() {
        return PlayerBusinessOperations.ACTIVITY_PROGRESS;
    }

    @Override
    public PlayerBusinessAck execute(PlayerGameExecution execution) {
        execution.runtime().activityService().increase(
                execution.profile().activities(),
                execution.activityAccess(),
                activityId,
                delta
        );
        return PlayerBusinessAck.OK;
    }
}
