package com.commonbattle.actor;

import java.util.EnumMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 类 Skynet 的 Actor 调度器。
 * 每个业务实体拥有独立邮箱，外部线程只负责投递消息；共享工作线程按批次调度邮箱，保证同一 Actor
 * 同一时刻只有一个线程执行，同时避免单个高热 Actor 长时间独占线程。
 */
public final class ActorSystem implements AutoCloseable {
    private final Map<ActorRef, ActorMailbox> mailboxes = new ConcurrentHashMap<>();
    private final Executor executor;
    private final AutoCloseable closeHook;
    private final ActorSystemConfig config;
    private final ActorFailureHandler failureHandler;
    private final DeadLetterSink deadLetters;
    private final ActorSystemMetrics metrics = new ActorSystemMetrics();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final int batchSize;

    public ActorSystem(int workerThreads) {
        this(ActorSystemConfig.defaults(workerThreads));
    }

    public ActorSystem(int workerThreads, int batchSize) {
        this(ActorSystemConfig.defaults(workerThreads).withBatchSize(batchSize));
    }

    public ActorSystem(ActorSystemConfig config) {
        this(config, ActorFailureHandler.ignore(), DeadLetterSink.ignore());
    }

    public ActorSystem(ActorSystemConfig config, ActorFailureHandler failureHandler, DeadLetterSink deadLetters) {
        this.config = Objects.requireNonNull(config, "config");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters");
        var service = Executors.newFixedThreadPool(config.workerThreads(), new ActorThreadFactory());
        this.executor = service;
        this.closeHook = () -> shutdown(service);
        this.batchSize = config.batchSize();
    }

    /**
     * 使用外部执行器创建调度器。
     * 主要用于嵌入已有线程池、测试确定性调度，或接入服务端统一线程治理。
     */
    public ActorSystem(Executor executor, int batchSize) {
        this(executor, batchSize, ActorSystemConfig.DEFAULT_MAILBOX_CAPACITY);
    }

    public ActorSystem(Executor executor, int batchSize, int mailboxCapacity) {
        this(executor, new ActorSystemConfig(1, batchSize, mailboxCapacity, ActorOverflowStrategy.REJECT, java.time.Duration.ZERO),
                ActorFailureHandler.ignore(), DeadLetterSink.ignore());
    }

    public ActorSystem(
            Executor executor,
            ActorSystemConfig config,
            ActorFailureHandler failureHandler,
            DeadLetterSink deadLetters
    ) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.config = Objects.requireNonNull(config, "config");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters");
        this.closeHook = () -> {
        };
        this.batchSize = config.batchSize();
    }

    public ActorRef actor(String id) {
        ActorRef ref = new ActorRef(id);
        mailboxes.computeIfAbsent(ref, ActorMailbox::new);
        return ref;
    }

    public void send(ActorRef target, ActorTask task) {
        if (!trySend(target, task)) {
            if (!accepting.get()) {
                throw new ActorSystemClosedException(target);
            }
            throw new MailboxFullException(target);
        }
    }

    public boolean trySend(ActorRef target, ActorTask task) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(task, "task");
        if (!accepting.get()) {
            reject(target, task, DeadLetterReason.SYSTEM_CLOSED);
            return false;
        }
        ActorMailbox mailbox = mailboxes.computeIfAbsent(target, ActorMailbox::new);
        ActorMailboxEnqueueResult enqueue = mailbox.enqueue(task, config);
        if (!enqueue.accepted()) {
            reject(target, task, enqueue.rejectionReason());
            return false;
        }
        if (enqueue.droppedTask() != null) {
            drop(target, enqueue.droppedTask());
        }
        metrics.taskSubmitted();
        schedule(mailbox);
        return true;
    }

    public ActorSystemStats stats() {
        int activeMailboxes = 0;
        int largestMailboxQueuedTasks = 0;
        String largestMailboxActorId = "";
        EnumMap<ActorTaskCategory, Integer> queuedByCategory = new EnumMap<>(ActorTaskCategory.class);
        for (ActorTaskCategory category : ActorTaskCategory.values()) {
            queuedByCategory.put(category, 0);
        }
        for (ActorMailbox mailbox : mailboxes.values()) {
            int queued = mailbox.size();
            if (queued > 0) {
                activeMailboxes++;
            }
            if (queued > largestMailboxQueuedTasks) {
                largestMailboxQueuedTasks = queued;
                largestMailboxActorId = mailbox.ref().id();
            }
            for (Map.Entry<ActorTaskCategory, Integer> entry : mailbox.categorySizes().entrySet()) {
                queuedByCategory.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        return metrics.snapshot(activeMailboxes, largestMailboxQueuedTasks, largestMailboxActorId, queuedByCategory);
    }

    /**
     * 返回当前仍有排队消息的邮箱快照，按积压量从高到低排序。
     */
    public List<ActorMailboxStats> queuedMailboxStats() {
        return mailboxes.values().stream()
                .map(mailbox -> new ActorMailboxStats(mailbox.ref(), mailbox.size(), mailbox.categorySizes()))
                .filter(stats -> stats.queuedTasks() > 0)
                .sorted(Comparator.comparingInt(ActorMailboxStats::queuedTasks)
                        .reversed()
                        .thenComparing(stats -> stats.actor().id()))
                .toList();
    }

    public boolean isAccepting() {
        return accepting.get();
    }

    private void schedule(ActorMailbox mailbox) {
        if (mailbox.trySchedule()) {
            metrics.mailboxScheduled();
            try {
                executor.execute(() -> drain(mailbox));
            } catch (RejectedExecutionException ignored) {
                mailbox.markIdle();
                metrics.mailboxIdle();
            }
        }
    }

    private void drain(ActorMailbox mailbox) {
        ActorContext context = new ActorContext(this, mailbox.ref());
        int processed = 0;
        try {
            ActorTask task;
            while ((!accepting.get() || processed < batchSize) && (task = mailbox.poll()) != null) {
                metrics.taskPolled();
                runTask(mailbox.ref(), task, context);
                processed++;
            }
        } finally {
            // 邮箱调度边界：先释放 RUNNING 标记，再检查是否有新消息，防止回调线程漏调度。
            mailbox.markIdle();
            metrics.mailboxIdle();
            if (accepting.get() && mailbox.hasTasks()) {
                schedule(mailbox);
            }
        }
    }

    private void runTask(ActorRef actor, ActorTask task, ActorContext context) {
        ActorTaskCategory category = categoryOf(task);
        long startedAt = System.nanoTime();
        Throwable failure = null;
        try {
            task.run(context);
        } catch (Throwable error) {
            failure = error;
        } finally {
            metrics.taskExecuted(actor, category, System.nanoTime() - startedAt, config.slowTaskThreshold());
        }
        if (failure == null) {
            metrics.taskCompleted();
            return;
        }
        // Actor 异常隔离边界：单条消息失败只上报监督处理器，不允许打断同邮箱后续消息调度。
        metrics.taskFailed();
        try {
            failureHandler.onFailure(new ActorFailure(actor, task, failure));
        } catch (Throwable ignored) {
        }
    }

    private void reject(ActorRef target, ActorTask task, DeadLetterReason reason) {
        metrics.taskRejected(categoryOf(task));
        try {
            deadLetters.accept(new DeadLetter(target, task, reason));
        } catch (Throwable ignored) {
        }
    }

    private void drop(ActorRef target, ActorTask task) {
        metrics.taskDropped(categoryOf(task));
        try {
            deadLetters.accept(new DeadLetter(target, task, DeadLetterReason.DROPPED_BY_OVERFLOW));
        } catch (Throwable ignored) {
        }
    }

    private static ActorTaskCategory categoryOf(ActorTask task) {
        ActorTaskCategory category = task.category();
        return category == null ? ActorTaskCategory.DEFAULT : category;
    }

    @Override
    public void close() {
        accepting.set(false);
        try {
            closeHook.close();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to close actor system", e);
        }
    }

    private void shutdown(ExecutorService service) throws InterruptedException {
        service.shutdown();
        if (!service.awaitTermination(config.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
            service.shutdownNow();
        }
    }

    private static final class ActorThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "common-battle-actor-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
