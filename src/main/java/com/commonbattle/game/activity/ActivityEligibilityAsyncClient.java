package com.commonbattle.game.activity;

/**
 * 活动异步资格服务客户端。
 * 可对接跨服活动中心、风控、联盟/队伍资格等外部判定源。
 */
public interface ActivityEligibilityAsyncClient {
    void check(long playerId, String activityId, ActivityEligibilityCallback callback);
}
