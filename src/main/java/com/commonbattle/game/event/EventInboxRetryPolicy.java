package com.commonbattle.game.event;

import java.time.Duration;
import java.util.Objects;

/**
 * 消费端 inbox 重试和毒消息隔离策略。
 */
public record EventInboxRetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        int replayBatchSize
) {
    public EventInboxRetryPolicy {
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

    public static EventInboxRetryPolicy defaults() {
        return new EventInboxRetryPolicy(10, Duration.ofMillis(100), Duration.ofMinutes(1), 256);
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
