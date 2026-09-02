package com.commonbattle.game.config;

/**
 * 业务进程配置预热结果状态。
 */
public enum GameConfigWarmupStatus {
    READY,
    FAILED,
    TIMEOUT,
    INTERRUPTED
}
