package com.commonbattle.game.snapshot;

/**
 * 通用快照读取状态。
 * 各业务可保留自己的结果枚举对外表达语义，但内部统计应统一映射到这些状态。
 */
public enum SnapshotReadStatus {
    LOCAL_HIT,
    LOCAL_STALE,
    LOCAL_MISS,
    REFRESHED,
    REMOTE_STALE,
    REMOTE_MISS,
    LOCAL_FALLBACK
}
