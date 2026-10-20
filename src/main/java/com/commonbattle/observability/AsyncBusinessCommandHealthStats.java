package com.commonbattle.observability;

import com.commonbattle.game.player.AsyncBusinessCommandStats;
import com.commonbattle.game.player.AsyncBusinessCommandOperationStats;

import java.util.Map;

/**
 * 玩家异步业务命令聚合健康统计。
 */
public record AsyncBusinessCommandHealthStats(
        int viewCount,
        long startedCommands,
        long currentPendingCommands,
        long completedCommands,
        long rejectedCommands,
        long failedCommands,
        long lateCallbacks,
        long abandonedPendingCommands,
        long callbackDeliveryFailures,
        Map<String, AsyncBusinessCommandOperationStats> operations
) {
    public AsyncBusinessCommandHealthStats {
        operations = operations == null ? Map.of() : Map.copyOf(operations);
    }

    public static AsyncBusinessCommandHealthStats empty() {
        return new AsyncBusinessCommandHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());
    }

    public static AsyncBusinessCommandHealthStats from(int viewCount, AsyncBusinessCommandStats stats) {
        return new AsyncBusinessCommandHealthStats(
                viewCount,
                stats.startedCommands(),
                stats.currentPendingCommands(),
                stats.completedCommands(),
                stats.rejectedCommands(),
                stats.failedCommands(),
                stats.lateCallbacks(),
                stats.abandonedPendingCommands(),
                stats.callbackDeliveryFailures(),
                stats.operations()
        );
    }
}
