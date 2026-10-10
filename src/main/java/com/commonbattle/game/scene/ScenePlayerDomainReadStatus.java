package com.commonbattle.game.scene;

/**
 * 场景玩家领域投影 fresh read 的来源状态。
 * 关键场景逻辑可用它区分本地命中、回源刷新、远端旧快照和远端缺失。
 */
public enum ScenePlayerDomainReadStatus {
    LOCAL_HIT,
    REFRESHED,
    REMOTE_STALE,
    REMOTE_MISS
}
