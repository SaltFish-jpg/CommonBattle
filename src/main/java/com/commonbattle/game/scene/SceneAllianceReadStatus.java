package com.commonbattle.game.scene;

/**
 * 场景联盟成员快照 fresh read 的来源状态。
 */
public enum SceneAllianceReadStatus {
    LOCAL_HIT,
    REFRESHED,
    REMOTE_STALE,
    REMOTE_MISS
}
