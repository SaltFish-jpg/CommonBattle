package com.commonbattle.game.profile;

/**
 * 玩家基础资料读取结果状态。
 * 业务可据此区分“本地可直接展示”和“已降级，需要延迟重试或走严格 RPC”的场景。
 */
public enum ProfileReadStatus {
    LOCAL_HIT,
    LOCAL_STALE,
    LOCAL_MISS,
    REFRESHED,
    REMOTE_STALE,
    REMOTE_MISS,
    LOCAL_FALLBACK
}
