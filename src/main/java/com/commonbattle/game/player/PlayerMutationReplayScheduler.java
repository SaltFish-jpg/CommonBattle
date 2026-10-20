package com.commonbattle.game.player;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 玩家事务事件的后台补交调度器。
 * 这里只向玩家 mailbox 投递重试信号，事件读取和 outbox 移交仍由玩家 Actor 串行完成。
 */
public final class PlayerMutationReplayScheduler implements AutoCloseable {
    private final PlayerGameAgentManager agents;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final LongAdder runs = new LongAdder();
    private final LongAdder submittedPlayers = new LongAdder();
    private final LongAdder failedRuns = new LongAdder();
    private volatile ScheduledFuture<?> task;

    public PlayerMutationReplayScheduler(PlayerGameAgentManager agents, Duration interval) {
        this(agents, interval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "player-mutation-event-replay");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    PlayerMutationReplayScheduler(
            PlayerGameAgentManager agents,
            Duration interval,
            ScheduledExecutorService scheduler
    ) {
        this(agents, interval, scheduler, false);
    }

    private PlayerMutationReplayScheduler(
            PlayerGameAgentManager agents,
            Duration interval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.interval = positive(interval);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(
                this::replaySafely,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public void replayOnce() {
        runs.increment();
        submittedPlayers.add(agents.replayPendingDomainEvents());
    }

    public PlayerMutationReplaySchedulerStats stats() {
        return new PlayerMutationReplaySchedulerStats(runs.sum(), submittedPlayers.sum(), failedRuns.sum());
    }

    @Override
    public void close() {
        ScheduledFuture<?> scheduled = task;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        started.set(false);
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private void replaySafely() {
        try {
            replayOnce();
        } catch (RuntimeException ignored) {
            failedRuns.increment();
        }
    }

    private static Duration positive(Duration interval) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        return interval;
    }
}
