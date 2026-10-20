package com.commonbattle.game.player;

/**
 * 单个玩家业务 operation 的异步命令统计。
 */
public record AsyncBusinessCommandOperationStats(
        String operation,
        long startedCommands,
        long currentPendingCommands,
        long completedCommands,
        long rejectedCommands,
        long failedCommands,
        long lateCallbacks,
        long abandonedPendingCommands,
        long callbackDeliveryFailures
) {
    public AsyncBusinessCommandOperationStats {
        operation = operation == null ? "" : operation;
    }

    public AsyncBusinessCommandOperationStats plus(AsyncBusinessCommandOperationStats other) {
        return new AsyncBusinessCommandOperationStats(
                operation.isBlank() ? other.operation() : operation,
                startedCommands + other.startedCommands,
                currentPendingCommands + other.currentPendingCommands,
                completedCommands + other.completedCommands,
                rejectedCommands + other.rejectedCommands,
                failedCommands + other.failedCommands,
                lateCallbacks + other.lateCallbacks,
                abandonedPendingCommands + other.abandonedPendingCommands,
                callbackDeliveryFailures + other.callbackDeliveryFailures
        );
    }
}
