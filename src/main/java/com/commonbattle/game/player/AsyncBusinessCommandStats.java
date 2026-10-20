package com.commonbattle.game.player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家异步业务命令统计。
 * 它统计的是所有需要外部异步依赖后再回投玩家 mailbox 的命令，不绑定具体玩法。
 */
public record AsyncBusinessCommandStats(
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
    public AsyncBusinessCommandStats {
        operations = operations == null ? Map.of() : Map.copyOf(operations);
    }

    public static AsyncBusinessCommandStats empty() {
        return new AsyncBusinessCommandStats(0, 0, 0, 0, 0, 0, 0, 0, Map.of());
    }

    public AsyncBusinessCommandStats plus(AsyncBusinessCommandStats other) {
        return new AsyncBusinessCommandStats(
                startedCommands + other.startedCommands,
                currentPendingCommands + other.currentPendingCommands,
                completedCommands + other.completedCommands,
                rejectedCommands + other.rejectedCommands,
                failedCommands + other.failedCommands,
                lateCallbacks + other.lateCallbacks,
                abandonedPendingCommands + other.abandonedPendingCommands,
                callbackDeliveryFailures + other.callbackDeliveryFailures,
                plusOperations(operations, other.operations)
        );
    }

    private static Map<String, AsyncBusinessCommandOperationStats> plusOperations(
            Map<String, AsyncBusinessCommandOperationStats> first,
            Map<String, AsyncBusinessCommandOperationStats> second
    ) {
        Map<String, AsyncBusinessCommandOperationStats> merged = new LinkedHashMap<>(first);
        second.forEach((operation, stats) -> merged.merge(operation, stats, AsyncBusinessCommandOperationStats::plus));
        return Map.copyOf(merged);
    }
}
