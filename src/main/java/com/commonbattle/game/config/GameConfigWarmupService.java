package com.commonbattle.game.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 业务进程配置启动门禁。
 * Game、Scene、Chat 等进程应在对外接客前完成预热，确保本地配置缓存已经可信。
 */
public final class GameConfigWarmupService {
    private final GameConfigRecoveryPort recovery;
    private final Clock clock;

    public GameConfigWarmupService(GameConfigRecoveryPort recovery, Clock clock) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public GameConfigWarmupResult warmup(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Instant startedAt = clock.instant();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<GameConfigApplyResult> applied = new AtomicReference<>();
        recovery.recover(result -> {
            applied.set(result);
            latch.countDown();
        });
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return GameConfigWarmupResult.timeout(elapsed(startedAt));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GameConfigWarmupResult.interrupted(elapsed(startedAt));
        }
        GameConfigApplyResult result = applied.get();
        if (result != null && result.status() == GameConfigApplyStatus.RECOVERED) {
            return GameConfigWarmupResult.ready(result, elapsed(startedAt));
        }
        return GameConfigWarmupResult.failed(result, elapsed(startedAt));
    }

    private Duration elapsed(Instant startedAt) {
        Duration elapsed = Duration.between(startedAt, clock.instant());
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }
}
