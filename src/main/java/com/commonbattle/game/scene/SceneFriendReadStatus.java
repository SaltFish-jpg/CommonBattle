package com.commonbattle.game.scene;

/**
 * 场景好友快照 fresh read 的来源状态。
 * 关键业务可用它区分本地命中、回源刷新、远端旧快照和远端缺失，避免把空结果误判成非好友。
 */
public enum SceneFriendReadStatus {
    LOCAL_HIT,
    REFRESHED,
    REMOTE_STALE,
    REMOTE_MISS
}
