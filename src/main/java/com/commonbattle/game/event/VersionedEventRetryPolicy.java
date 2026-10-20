package com.commonbattle.game.event;

import java.time.Duration;
import java.util.Objects;

/**
 * 版本事件 outbox 重试策略。
 * 控制指数退避、单次补发批量和进入死信队列前允许的失败次数。
 */
public record VersionedEventRetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        int replayBatchSize
) {
    public VersionedEventRetryPolicy {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff must not be negative");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be shorter than initialBackoff");
        }
        if (replayBatchSize <= 0) {
            throw new IllegalArgumentException("replayBatchSize must be positive");
        }
    }

    public static VersionedEventRetryPolicy defaults() {
        return new VersionedEventRetryPolicy(20, Duration.ofSeconds(1), Duration.ofMinutes(5), 256);
    }

    public Duration backoffAfterFailure(int attempts) {
        if (attempts <= 0 || initialBackoff.isZero()) {
            return Duration.ZERO;
        }
        long initialMillis = initialBackoff.toMillis();
        long maxMillis = maxBackoff.toMillis();
        int shift = Math.min(attempts - 1, 62);
        long multiplier = 1L << shift;
        if (initialMillis > 0 && multiplier > maxMillis / initialMillis) {
            return maxBackoff;
        }
        return Duration.ofMillis(Math.min(maxMillis, initialMillis * multiplier));
    }
}
