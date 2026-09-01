package com.commonbattle.actor.message;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 ScheduledExecutorService 的 ask 超时调度器。
 */
public final class ExecutorAskTimeoutScheduler implements AskTimeoutScheduler {
    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;

    public ExecutorAskTimeoutScheduler() {
        this(Executors.newSingleThreadScheduledExecutor(new AskThreadFactory()), true);
    }

    public ExecutorAskTimeoutScheduler(ScheduledExecutorService executor) {
        this(executor, false);
    }

    private ExecutorAskTimeoutScheduler(ScheduledExecutorService executor, boolean ownsExecutor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
    }

    @Override
    public ScheduledAskTimeout schedule(Duration timeout, Runnable action) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        var future = executor.schedule(action, timeout.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    private static final class AskThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "common-battle-ask-timeout-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
