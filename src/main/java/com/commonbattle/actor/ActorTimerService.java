package com.commonbattle.actor;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Actor 定时器服务。
 * 定时线程只负责到点投递消息，所有业务逻辑仍在目标 Actor 邮箱中串行执行。
 */
public final class ActorTimerService implements AutoCloseable {
    private final ActorSystem system;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    public ActorTimerService(ActorSystem system) {
        this(system, Executors.newSingleThreadScheduledExecutor(new TimerThreadFactory()), true);
    }

    public ActorTimerService(ActorSystem system, ScheduledExecutorService scheduler) {
        this(system, scheduler, false);
    }

    private ActorTimerService(ActorSystem system, ScheduledExecutorService scheduler, boolean ownsScheduler) {
        this.system = Objects.requireNonNull(system, "system");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public ActorTimerHandle scheduleOnce(ActorRef target, Duration delay, ActorTask task) {
        Objects.requireNonNull(delay, "delay");
        validate(target, task);
        ScheduledFuture<?> future = scheduler.schedule(
                () -> system.trySend(target, task),
                delay.toMillis(),
                TimeUnit.MILLISECONDS
        );
        return new ScheduledActorTimer(future);
    }

    public ActorTimerHandle scheduleAtFixedRate(ActorRef target, Duration initialDelay, Duration period, ActorTask task) {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(period, "period");
        validate(target, task);
        AtomicReference<ScheduledFuture<?>> holder = new AtomicReference<>();
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> {
                    boolean accepted = system.trySend(target, task);
                    if (!accepted && !system.isAccepting()) {
                        ScheduledFuture<?> scheduled = holder.get();
                        if (scheduled != null) {
                            scheduled.cancel(false);
                        }
                    }
                },
                initialDelay.toMillis(),
                period.toMillis(),
                TimeUnit.MILLISECONDS
        );
        holder.set(future);
        return new ScheduledActorTimer(future);
    }

    private static void validate(ActorRef target, ActorTask task) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(task, "task");
    }

    @Override
    public void close() {
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private record ScheduledActorTimer(ScheduledFuture<?> future) implements ActorTimerHandle {
        @Override
        public boolean cancel() {
            return future.cancel(false);
        }

        @Override
        public boolean isCancelled() {
            return future.isCancelled();
        }
    }

    private static final class TimerThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "common-battle-actor-timer-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
