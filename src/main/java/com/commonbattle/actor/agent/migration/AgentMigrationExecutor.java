package com.commonbattle.actor.agent.migration;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Agent 迁移完成执行器。
 * 源 Actor 邮箱只负责打包和目录 move，目标接收、回滚和业务回调在该执行器中完成。
 */
public final class AgentMigrationExecutor implements Executor, AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final LongAdder submitted = new LongAdder();
    private final LongAdder running = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder rejected = new LongAdder();

    public AgentMigrationExecutor(String threadNamePrefix, int threads, int queueCapacity) {
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        executor = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new NamedThreadFactory(threadNamePrefix),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        submitted.increment();
        try {
            executor.execute(() -> run(command));
        } catch (RejectedExecutionException e) {
            rejected.increment();
            throw e;
        }
    }

    public AgentMigrationExecutorStats stats() {
        return new AgentMigrationExecutorStats(
                submitted.sum(),
                running.sum(),
                completed.sum(),
                failed.sum(),
                rejected.sum(),
                executor.getQueue().size()
        );
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void run(Runnable command) {
        running.increment();
        try {
            command.run();
            completed.increment();
        } catch (RuntimeException | Error e) {
            failed.increment();
            throw e;
        } finally {
            running.decrement();
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger nextId = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = Objects.requireNonNull(prefix, "prefix");
            if (prefix.isBlank()) {
                throw new IllegalArgumentException("threadNamePrefix must not be blank");
            }
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
