package com.commonbattle.game.config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置运行期自动恢复协调器。
 * 本地缓存发现事件跳号后触发恢复；同一时间最多一个恢复请求在飞，避免事件风暴打爆中心服。
 */
public final class GameConfigAutoRecovery implements GameConfigRecoveryTrigger {
    private final GameConfigRecoveryPort recovery;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicInteger requested = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger succeeded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicReference<GameConfigApplyResult> lastResult = new AtomicReference<>();

    public GameConfigAutoRecovery(GameConfigRecoveryPort recovery) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
    }

    @Override
    public void onGap(GameConfigApplyResult gapResult) {
        requested.incrementAndGet();
        if (!inFlight.compareAndSet(false, true)) {
            skipped.incrementAndGet();
            return;
        }
        try {
            recovery.recover(this::complete);
        } catch (RuntimeException e) {
            complete(GameConfigApplyResult.recoveryFailed(gapResult.eventRevision(), e.getMessage()));
        }
    }

    public GameConfigAutoRecoveryStats stats() {
        return new GameConfigAutoRecoveryStats(
                requested.get(),
                skipped.get(),
                succeeded.get(),
                failed.get(),
                inFlight.get()
        );
    }

    public java.util.Optional<GameConfigApplyResult> lastResult() {
        return java.util.Optional.ofNullable(lastResult.get());
    }

    private void complete(GameConfigApplyResult result) {
        lastResult.set(result);
        if (result.status() == GameConfigApplyStatus.RECOVERED) {
            succeeded.incrementAndGet();
        } else {
            failed.incrementAndGet();
        }
        inFlight.set(false);
    }
}
