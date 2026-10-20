package com.commonbattle.game.event;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存版 outbox，适合单元测试和本地进程样例。
 * 生产环境应替换为与业务状态同事务写入的 DB outbox 表。
 */
public final class InMemoryVersionedEventOutbox implements VersionedEventOutbox {
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<Long, PendingVersionedEvent> pending = new ConcurrentHashMap<>();
    private final Map<Long, DeadLetteredVersionedEvent> deadLetters = new ConcurrentHashMap<>();

    public InMemoryVersionedEventOutbox(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PendingVersionedEvent append(VersionedEvent event) {
        Objects.requireNonNull(event, "event");
        long id = sequence.incrementAndGet();
        PendingVersionedEvent entry = new PendingVersionedEvent(id, event, clock.instant(), 0);
        pending.put(id, entry);
        return entry;
    }

    @Override
    public List<PendingVersionedEvent> pending() {
        return pending.values().stream()
                .sorted(Comparator.comparingLong(PendingVersionedEvent::id))
                .toList();
    }

    @Override
    public List<PendingVersionedEvent> ready(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return pending.values().stream()
                .filter(entry -> !entry.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparingLong(PendingVersionedEvent::id))
                .limit(limit)
                .toList();
    }

    @Override
    public List<DeadLetteredVersionedEvent> deadLetters() {
        return deadLetters.values().stream()
                .sorted(Comparator.comparingLong(DeadLetteredVersionedEvent::id))
                .toList();
    }

    @Override
    public void markPublished(long outboxId) {
        pending.remove(outboxId);
        deadLetters.remove(outboxId);
    }

    @Override
    public void markAttemptFailed(long outboxId) {
        Instant now = clock.instant();
        markAttemptFailed(
                outboxId,
                new IllegalStateException("event publish failed"),
                now,
                now,
                Integer.MAX_VALUE
        );
    }

    @Override
    public OutboxFailureResult markAttemptFailed(
            long outboxId,
            Throwable failure,
            Instant failedAt,
            Instant nextAttemptAt,
            int maxAttempts
    ) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        AtomicReference<OutboxFailureResult> result = new AtomicReference<>(OutboxFailureResult.ENTRY_NOT_FOUND);
        pending.computeIfPresent(outboxId, (ignored, entry) -> {
            int attempts = entry.attempts() + 1;
            String message = failureMessage(failure);
            if (attempts >= maxAttempts) {
                deadLetters.put(outboxId, new DeadLetteredVersionedEvent(
                        entry.id(), entry.event(), entry.createdAt(), attempts, failedAt, message
                ));
                result.set(OutboxFailureResult.DEAD_LETTERED);
                return null;
            }
            result.set(OutboxFailureResult.RETRY_SCHEDULED);
            return new PendingVersionedEvent(
                    entry.id(), entry.event(), entry.createdAt(), attempts, nextAttemptAt, message
            );
        });
        return result.get();
    }

    @Override
    public boolean redriveDeadLetter(long outboxId, Instant nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        AtomicReference<DeadLetteredVersionedEvent> removed = new AtomicReference<>();
        deadLetters.computeIfPresent(outboxId, (ignored, entry) -> {
            removed.set(entry);
            return null;
        });
        DeadLetteredVersionedEvent entry = removed.get();
        if (entry == null) {
            return false;
        }
        pending.put(outboxId, new PendingVersionedEvent(
                entry.id(), entry.event(), entry.createdAt(), 0, nextAttemptAt, ""
        ));
        return true;
    }

    private static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }
}
