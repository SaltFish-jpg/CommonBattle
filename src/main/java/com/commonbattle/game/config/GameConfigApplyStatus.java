package com.commonbattle.game.config;

/**
 * 本地配置缓存应用事件的结果。
 */
public enum GameConfigApplyStatus {
    APPLIED,
    DUPLICATE_OR_OLD,
    GAP,
    REJECTED,
    RECOVERED,
    RECOVERY_FAILED
}
