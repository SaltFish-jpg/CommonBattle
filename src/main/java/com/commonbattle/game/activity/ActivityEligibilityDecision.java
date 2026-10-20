package com.commonbattle.game.activity;

import java.util.Objects;

/**
 * 活动异步资格判定结果。
 * 外部资格服务只返回是否允许参与，真正的活动领奖和背包变更仍应回到玩家 Actor 内结算。
 */
public record ActivityEligibilityDecision(boolean allowed, String reason) {
    public ActivityEligibilityDecision {
        reason = reason == null ? "" : reason;
    }

    public static ActivityEligibilityDecision allow() {
        return new ActivityEligibilityDecision(true, "");
    }

    public static ActivityEligibilityDecision denied(String reason) {
        return new ActivityEligibilityDecision(false,
                Objects.requireNonNullElse(reason, "activity_not_eligible"));
    }
}
