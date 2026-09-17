package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorScheduleKey;
import com.commonbattle.actor.ActorScheduleRegistry;
import com.commonbattle.actor.ActorTimerHandle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家自动保存调度器。
 * 生产环境优先由 ActorScheduleRegistry 投递到自动保存 Actor，再由该 Actor 向玩家邮箱提交保存请求；
 * 实际快照始终在各玩家 Actor 邮箱内串行生成。
 */
public final class PlayerAutoSaveScheduler implements AutoCloseable {
    private final PlayerAutoSaveTarget target;
    private final ScheduledExecutorService scheduler;
    private final ActorScheduleRegistry actorSchedules;
    private final ActorRef schedulerActor;
    private final ActorScheduleKey scheduleKey;
    private final boolean ownsScheduler;
    private final Duration initialDelay;
    private final Duration interval;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong runs = new AtomicLong();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failedRuns = new AtomicLong();
    private final AtomicLong failedSaves = new AtomicLong();
    private volatile ScheduledFuture<?> future;
    private volatile ActorTimerHandle actorTimer;

    public PlayerAutoSaveScheduler(
            PlayerGameAgentManager agents,
            Duration initialDelay,
            Duration interval
    ) {
        this(Objects.requireNonNull(agents, "agents")::saveAllLoaded, initialDelay, interval,
                Executors.newSingleThreadScheduledExecutor(new AutoSaveThreadFactory()), true);
    }

    public PlayerAutoSaveScheduler(
            PlayerGameAgentManager agents,
            ActorScheduleRegistry actorSchedules,
            ActorRef schedulerActor,
            Duration initialDelay,
            Duration interval
    ) {
        this(Objects.requireNonNull(agents, "agents")::saveAllLoaded, initialDelay, interval,
                actorSchedules, schedulerActor, new ActorScheduleKey("player.autosave", schedulerActor.id()));
    }

    public PlayerAutoSaveScheduler(
            PlayerGameAgentManager agents,
            Duration initialDelay,
            Duration interval,
            ScheduledExecutorService scheduler
    ) {
        this(Objects.requireNonNull(agents, "agents")::saveAllLoaded, initialDelay, interval, scheduler, false);
    }

    PlayerAutoSaveScheduler(
            PlayerAutoSaveTarget target,
            Duration initialDelay,
            Duration interval
    ) {
        this(target, initialDelay, interval,
                Executors.newSingleThreadScheduledExecutor(new AutoSaveThreadFactory()), true);
    }

    private PlayerAutoSaveScheduler(
            PlayerAutoSaveTarget target,
            Duration initialDelay,
            Duration interval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.target = Objects.requireNonNull(target, "target");
        this.initialDelay = positiveOrZero(initialDelay, "initialDelay");
        this.interval = positive(interval, "interval");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.actorSchedules = null;
        this.schedulerActor = null;
        this.scheduleKey = null;
        this.ownsScheduler = ownsScheduler;
    }

    private PlayerAutoSaveScheduler(
            PlayerAutoSaveTarget target,
            Duration initialDelay,
            Duration interval,
            ActorScheduleRegistry actorSchedules,
            ActorRef schedulerActor,
            ActorScheduleKey scheduleKey
    ) {
        this.target = Objects.requireNonNull(target, "target");
        this.initialDelay = positiveOrZero(initialDelay, "initialDelay");
        this.interval = positive(interval, "interval");
        this.scheduler = null;
        this.actorSchedules = Objects.requireNonNull(actorSchedules, "actorSchedules");
        this.schedulerActor = Objects.requireNonNull(schedulerActor, "schedulerActor");
        this.scheduleKey = Objects.requireNonNull(scheduleKey, "scheduleKey");
        this.ownsScheduler = false;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (actorSchedules != null) {
            actorTimer = actorSchedules.scheduleAtFixedRate(
                    scheduleKey,
                    schedulerActor,
                    initialDelay,
                    interval,
                    ignored -> runOnceSafely()
            );
            return;
        }
        future = scheduler.scheduleAtFixedRate(
                this::runOnceSafely,
                initialDelay.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public int runOnce() {
        runs.incrementAndGet();
        try {
            int count = target.saveAllLoaded(new PlayerStateSaveCallback() {
                @Override
                public void saved(PlayerStateSnapshot snapshot) {
                    completed.incrementAndGet();
                }

                @Override
                public void failed(long playerId, RuntimeException error) {
                    failedSaves.incrementAndGet();
                }
            });
            submitted.addAndGet(count);
            return count;
        } catch (RuntimeException e) {
            failedRuns.incrementAndGet();
            throw e;
        }
    }

    public PlayerAutoSaveStats stats() {
        return new PlayerAutoSaveStats(runs.get(), submitted.get(), completed.get(), failedRuns.get(), failedSaves.get());
    }

    private void runOnceSafely() {
        if (closed.get()) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void close() {
        closed.set(true);
        ScheduledFuture<?> scheduled = future;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        ActorTimerHandle timer = actorTimer;
        if (timer != null) {
            timer.cancel();
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration positiveOrZero(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static final class AutoSaveThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "common-battle-player-autosave-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}

@FunctionalInterface
interface PlayerAutoSaveTarget {
    int saveAllLoaded(PlayerStateSaveCallback callback);
}
