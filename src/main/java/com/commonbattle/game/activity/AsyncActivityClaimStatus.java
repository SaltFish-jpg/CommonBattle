package com.commonbattle.game.activity;

/**
 * 异步活动领奖状态。
 */
public enum AsyncActivityClaimStatus {
    CLAIMED,
    DENIED,
    CHECK_FAILED,
    CLAIM_FAILED,
    BACKPRESSURED
}
