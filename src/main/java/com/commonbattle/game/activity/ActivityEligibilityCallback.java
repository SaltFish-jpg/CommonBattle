package com.commonbattle.game.activity;

/**
 * 活动异步资格回调。
 * 实现方通常运行在 RPC、Netty 或外部执行器线程中，调用方必须只把结果投递回玩家 Actor。
 */
public interface ActivityEligibilityCallback {
    void success(ActivityEligibilityDecision decision);

    void failure(Throwable error);
}
