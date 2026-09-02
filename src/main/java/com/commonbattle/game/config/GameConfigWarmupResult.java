package com.commonbattle.game.config;

import java.time.Duration;

/**
 * 业务进程启动时配置预热的结果。
 */
public record GameConfigWarmupResult(
        GameConfigWarmupStatus status,
        GameConfigApplyResult applyResult,
        Duration elapsed,
        String message
) {
    public boolean ready() {
        return status == GameConfigWarmupStatus.READY;
    }

    public static GameConfigWarmupResult ready(GameConfigApplyResult applyResult, Duration elapsed) {
        return new GameConfigWarmupResult(GameConfigWarmupStatus.READY, applyResult, elapsed, "");
    }

    public static GameConfigWarmupResult failed(GameConfigApplyResult applyResult, Duration elapsed) {
        return new GameConfigWarmupResult(GameConfigWarmupStatus.FAILED, applyResult, elapsed, applyResult.message());
    }

    public static GameConfigWarmupResult timeout(Duration elapsed) {
        return new GameConfigWarmupResult(GameConfigWarmupStatus.TIMEOUT, null, elapsed, "config warmup timeout");
    }

    public static GameConfigWarmupResult interrupted(Duration elapsed) {
        return new GameConfigWarmupResult(GameConfigWarmupStatus.INTERRUPTED, null, elapsed, "config warmup interrupted");
    }
}
