package com.commonbattle.game.activity;

import com.commonbattle.game.GameBusinessFailure;
import com.commonbattle.game.GameBusinessResultStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * 异步活动领奖结果。
 * 用于表达外部资格校验、玩家 Actor 回投背压和玩家 mailbox 内最终领奖结算的完整结果。
 */
public record AsyncActivityClaimResult(
        String activityId,
        AsyncActivityClaimStatus status,
        Optional<ActivityClaimResult> claim,
        String reason
) implements GameBusinessResultStatus {
    public AsyncActivityClaimResult {
        Objects.requireNonNull(activityId, "activityId");
        Objects.requireNonNull(status, "status");
        claim = claim == null ? Optional.empty() : claim;
        reason = reason == null ? "" : reason;
    }

    public static AsyncActivityClaimResult claimed(ActivityClaimResult result) {
        Objects.requireNonNull(result, "result");
        return new AsyncActivityClaimResult(result.activityId(), AsyncActivityClaimStatus.CLAIMED,
                Optional.of(result), "");
    }

    public static AsyncActivityClaimResult denied(String activityId, String reason) {
        return new AsyncActivityClaimResult(activityId, AsyncActivityClaimStatus.DENIED,
                Optional.empty(), reason);
    }

    public static AsyncActivityClaimResult checkFailed(String activityId, Throwable error) {
        return failed(activityId, AsyncActivityClaimStatus.CHECK_FAILED, error);
    }

    public static AsyncActivityClaimResult claimFailed(String activityId, Throwable error) {
        return failed(activityId, AsyncActivityClaimStatus.CLAIM_FAILED, error);
    }

    public static AsyncActivityClaimResult backpressured(String activityId) {
        return new AsyncActivityClaimResult(activityId, AsyncActivityClaimStatus.BACKPRESSURED,
                Optional.empty(), "player_mailbox_backpressured");
    }

    @Override
    public boolean businessResultRejected() {
        return status != AsyncActivityClaimStatus.CLAIMED;
    }

    @Override
    public String businessResultCode() {
        return switch (status) {
            case CLAIMED -> "OK";
            case DENIED -> "ACTIVITY_ELIGIBILITY_DENIED";
            case CHECK_FAILED -> "ACTIVITY_ELIGIBILITY_CHECK_FAILED";
            case CLAIM_FAILED -> reason.isBlank() ? "ACTIVITY_CLAIM_FAILED" : reason;
            case BACKPRESSURED -> "ACTIVITY_ASYNC_BACKPRESSURED";
        };
    }

    private static AsyncActivityClaimResult failed(
            String activityId,
            AsyncActivityClaimStatus status,
            Throwable error
    ) {
        String reason = error instanceof GameBusinessFailure failure
                ? failure.code()
                : error == null ? "" : error.getClass().getSimpleName();
        return new AsyncActivityClaimResult(activityId, status, Optional.empty(), reason);
    }
}
