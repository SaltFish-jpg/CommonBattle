package com.commonbattle.game.activity;

import com.commonbattle.game.bag.BagResult;

/**
 * 活动领奖结果。
 */
public record ActivityClaimResult(String activityId, int progress, BagResult bagResult) {
}
