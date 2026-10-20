package com.commonbattle.game.player;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家异步业务命令指标累加器。
 * 回调线程和玩家 mailbox 线程只做原子计数，不参与业务同步。
 */
public final class AsyncBusinessCommandMetrics implements AsyncBusinessCommandView {
    private final AtomicLong startedCommands = new AtomicLong();
    private final AtomicLong currentPendingCommands = new AtomicLong();
    private final AtomicLong completedCommands = new AtomicLong();
    private final AtomicLong rejectedCommands = new AtomicLong();
    private final AtomicLong failedCommands = new AtomicLong();
    private final AtomicLong lateCallbacks = new AtomicLong();
    private final AtomicLong abandonedPendingCommands = new AtomicLong();
    private final AtomicLong callbackDeliveryFailures = new AtomicLong();
    private final ConcurrentHashMap<String, OperationCounters> operations = new ConcurrentHashMap<>();

    void commandStarted(String operation) {
        startedCommands.incrementAndGet();
        counters(operation).startedCommands.incrementAndGet();
    }

    void pendingCommandStarted(String operation) {
        currentPendingCommands.incrementAndGet();
        counters(operation).currentPendingCommands.incrementAndGet();
    }

    void pendingCommandFinished(String operation) {
        currentPendingCommands.updateAndGet(current -> Math.max(0, current - 1));
        counters(operation).currentPendingCommands.updateAndGet(current -> Math.max(0, current - 1));
    }

    void commandCompleted(String operation, boolean rejected) {
        completedCommands.incrementAndGet();
        OperationCounters counters = counters(operation);
        counters.completedCommands.incrementAndGet();
        if (rejected) {
            rejectedCommands.incrementAndGet();
            counters.rejectedCommands.incrementAndGet();
        }
    }

    void commandFailed(String operation) {
        failedCommands.incrementAndGet();
        counters(operation).failedCommands.incrementAndGet();
    }

    void lateCallback(String operation) {
        lateCallbacks.incrementAndGet();
        counters(operation).lateCallbacks.incrementAndGet();
    }

    void abandonedPendingCommand(String operation) {
        abandonedPendingCommands.incrementAndGet();
        counters(operation).abandonedPendingCommands.incrementAndGet();
    }

    void callbackDeliveryFailure(String operation) {
        callbackDeliveryFailures.incrementAndGet();
        counters(operation).callbackDeliveryFailures.incrementAndGet();
    }

    @Override
    public AsyncBusinessCommandStats asyncBusinessCommandStats() {
        return new AsyncBusinessCommandStats(
                startedCommands.get(),
                currentPendingCommands.get(),
                completedCommands.get(),
                rejectedCommands.get(),
                failedCommands.get(),
                lateCallbacks.get(),
                abandonedPendingCommands.get(),
                callbackDeliveryFailures.get(),
                operationStats()
        );
    }

    private OperationCounters counters(String operation) {
        String key = Objects.requireNonNullElse(operation, "");
        return operations.computeIfAbsent(key, ignored -> new OperationCounters());
    }

    private Map<String, AsyncBusinessCommandOperationStats> operationStats() {
        Map<String, AsyncBusinessCommandOperationStats> result = new java.util.LinkedHashMap<>();
        operations.forEach((operation, counters) -> result.put(operation, counters.snapshot(operation)));
        return Map.copyOf(result);
    }

    private static final class OperationCounters {
        private final AtomicLong startedCommands = new AtomicLong();
        private final AtomicLong currentPendingCommands = new AtomicLong();
        private final AtomicLong completedCommands = new AtomicLong();
        private final AtomicLong rejectedCommands = new AtomicLong();
        private final AtomicLong failedCommands = new AtomicLong();
        private final AtomicLong lateCallbacks = new AtomicLong();
        private final AtomicLong abandonedPendingCommands = new AtomicLong();
        private final AtomicLong callbackDeliveryFailures = new AtomicLong();

        private AsyncBusinessCommandOperationStats snapshot(String operation) {
            return new AsyncBusinessCommandOperationStats(
                    operation,
                    startedCommands.get(),
                    currentPendingCommands.get(),
                    completedCommands.get(),
                    rejectedCommands.get(),
                    failedCommands.get(),
                    lateCallbacks.get(),
                    abandonedPendingCommands.get(),
                    callbackDeliveryFailures.get()
            );
        }
    }
}
