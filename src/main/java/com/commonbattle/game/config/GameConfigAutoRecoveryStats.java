package com.commonbattle.game.config;

/**
 * 配置运行期自动恢复统计。
 */
public record GameConfigAutoRecoveryStats(
        int requested,
        int skippedWhileInFlight,
        int succeeded,
        int failed,
        boolean inFlight
) {
}
