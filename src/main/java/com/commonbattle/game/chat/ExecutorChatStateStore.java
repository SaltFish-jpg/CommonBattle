package com.commonbattle.game.chat;

import com.commonbattle.actor.agent.AgentIdentity;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 使用有界专用线程池隔离阻塞存储的 Chat 状态仓储。
 */
public final class ExecutorChatStateStore implements AsyncChatStateStore, AutoCloseable {
    private final ChatStateStore delegate;
    private final ThreadPoolExecutor executor;

    public ExecutorChatStateStore(
            ChatStateStore delegate,
            int workers,
            int queueCapacity,
            String threadNamePrefix
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (workers <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("workers and queueCapacity must be positive");
        }
        this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new StoreThreadFactory(threadNamePrefix),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public void load(
            AgentIdentity identity,
            Consumer<Optional<VersionedChatState>> success,
            Consumer<Throwable> failure
    ) {
        submit(() -> success.accept(delegate.load(identity)), failure);
    }

    @Override
    public void save(
            AgentIdentity identity,
            long expectedVersion,
            long ownerToken,
            ChatChannelSnapshot snapshot,
            Consumer<ChatStateSaveResult> success,
            Consumer<Throwable> failure
    ) {
        submit(() -> success.accept(delegate.save(identity, expectedVersion, ownerToken, snapshot)), failure);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void submit(Runnable task, Consumer<Throwable> failure) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(failure, "failure");
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable error) {
                    failure.accept(error);
                }
            });
        } catch (RejectedExecutionException error) {
            failure.accept(error);
        }
    }

    private static final class StoreThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger next = new AtomicInteger(1);

        private StoreThreadFactory(String prefix) {
            this.prefix = Objects.requireNonNull(threadNamePrefix(prefix), "threadNamePrefix");
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + next.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }

        private static String threadNamePrefix(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("threadNamePrefix must not be blank");
            }
            return value;
        }
    }
}
