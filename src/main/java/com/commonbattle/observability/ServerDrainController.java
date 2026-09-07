package com.commonbattle.observability;

import com.commonbattle.runtime.DrainableComponent;
import com.commonbattle.runtime.DrainPhase;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 服务停服排水控制器。
 * 它基于健康快照等待 Actor 队列、RPC pending 和 outbox pending 清空。
 */
public final class ServerDrainController {
    private final RuntimeHealthProbe probe;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Collection<DrainableComponent> drainableComponents;

    public ServerDrainController(RuntimeHealthProbe probe, Clock clock) {
        this(probe, clock, duration -> Thread.sleep(duration.toMillis()));
    }

    public ServerDrainController(RuntimeHealthProbe probe, Clock clock, Sleeper sleeper) {
        this(probe, clock, sleeper, List.of());
    }

    public ServerDrainController(
            RuntimeHealthProbe probe,
            Clock clock,
            Sleeper sleeper,
            Collection<DrainableComponent> drainableComponents
    ) {
        this.probe = Objects.requireNonNull(probe, "probe");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.drainableComponents = List.copyOf(Objects.requireNonNull(drainableComponents, "drainableComponents"));
    }

    public DrainResult awaitDrained(DrainConfig config) throws InterruptedException {
        Objects.requireNonNull(config, "config");
        long start = clock.millis();
        try {
            beginDrain(DrainPhase.EXTERNAL_ADVERTISEMENT);
            if (!config.propagationDelay().isZero()) {
                sleeper.sleep(config.propagationDelay());
            }
            beginDrain(DrainPhase.LOCAL_INGRESS);
        } catch (RuntimeException e) {
            return new DrainResult(false, Duration.ofMillis(clock.millis() - start), probe.snapshot(),
                    "begin_drain_failed:" + e.getClass().getSimpleName());
        }
        RuntimeHealthSnapshot snapshot = probe.snapshot();
        while (!drained(snapshot)) {
            long elapsed = clock.millis() - start;
            if (elapsed >= config.timeout().toMillis()) {
                return new DrainResult(false, Duration.ofMillis(elapsed), snapshot);
            }
            sleeper.sleep(config.pollInterval());
            snapshot = probe.snapshot();
        }
        return new DrainResult(true, Duration.ofMillis(clock.millis() - start), snapshot);
    }

    private void beginDrain(DrainPhase phase) {
        drainableComponents.stream()
                .filter(component -> component.phase() == phase)
                .forEach(DrainableComponent::beginDrain);
    }

    private boolean drained(RuntimeHealthSnapshot snapshot) {
        return snapshot.actorSystem().queuedTasks() == 0
                && snapshot.rpc().pendingRequests() == 0
                && snapshot.outbox().pendingEvents() == 0
                && snapshot.playerAgents().drainPending() == 0
                && snapshot.playerAgents().drainFailedSaves() == 0;
    }

    /**
     * 测试和生产可替换的休眠接口。
     */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
