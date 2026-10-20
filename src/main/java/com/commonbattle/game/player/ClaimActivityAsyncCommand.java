package com.commonbattle.game.player;

import com.commonbattle.game.activity.AsyncActivityClaimResult;

import java.util.Objects;

/**
 * 异步活动领奖命令。
 * 协议只携带 activityId，外部资格服务由命令处理器注入，资格回包必须重新投递回玩家 mailbox 后再结算奖励。
 */
public record ClaimActivityAsyncCommand(String activityId) implements PlayerBusinessCommand<AsyncActivityClaimResult> {
    public ClaimActivityAsyncCommand {
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
    public AsyncActivityClaimResult execute(PlayerGameExecution execution) {
        throw new UnsupportedOperationException("async activity claim must be executed with eligibility client");
    }
}
