package com.commonbattle.game.event;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存版版本事件 inbox，适合单元测试和本地进程样例。
 */
public final class InMemoryVersionedEventInbox implements VersionedEventInbox {
    private final Map<EventInboxKey, PendingEventInboxEntry> pending = new ConcurrentHashMap<>();
    private final Map<EventInboxKey, DeadLetteredEventInboxEntry> deadLetters = new ConcurrentHashMap<>();

    @Override
    public synchronized EventInboxAppendStatus appendIfAbsent(VersionedEvent event, Instant receivedAt) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(receivedAt, "receivedAt");
        EventInboxKey key = EventInboxKey.of(event);
        if (deadLetters.containsKey(key)) {
            return EventInboxAppendStatus.DEAD_LETTERED;
        }
        PendingEventInboxEntry appended = new PendingEventInboxEntry(key, event, receivedAt, 0, receivedAt, "");
        return pending.putIfAbsent(key, appended) == null
                ? EventInboxAppendStatus.APPENDED
                : EventInboxAppendStatus.ALREADY_PENDING;
    }

    @Override
    public Optional<PendingEventInboxEntry> nextReady(String topic, String ownerKey, Instant now) {
        Objects.requireNonNull(now, "now");
        if (blocked(topic, ownerKey)) {
            return Optional.empty();
        }
        return pending.values().stream()
                .filter(entry -> entry.key().topic().equals(topic) && entry.key().ownerKey().equals(ownerKey))
                .min(Comparator.comparingLong(entry -> entry.key().revision()))
                .filter(entry -> !entry.nextAttemptAt().isAfter(now));
    }

    @Override
    public List<PendingEventInboxEntry> ready(String topic, Instant now, int limit) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(now, "now");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return pending.values().stream()
                .filter(entry -> entry.key().topic().equals(topic))
                .filter(entry -> isEarliest(entry) && !blocked(topic, entry.key().ownerKey()))
                .filter(entry -> !entry.nextAttemptAt().isAfter(now))
                .sorted(entryOrder())
                .limit(limit)
                .toList();
    }

    @Override
    public List<PendingEventInboxEntry> pending(String topic) {
        return pending.values().stream()
                .filter(entry -> entry.key().topic().equals(topic))
                .sorted(entryOrder())
                .toList();
    }

    @Override
    public List<DeadLetteredEventInboxEntry> deadLetters(String topic) {
        return deadLetters.values().stream()
                .filter(entry -> entry.key().topic().equals(topic))
                .sorted(Comparator.comparing(DeadLetteredEventInboxEntry::receivedAt)
                        .thenComparing(entry -> entry.key().ownerKey())
                        .thenComparingLong(entry -> entry.key().revision()))
                .toList();
    }

    @Override
    public synchronized void markHandled(EventInboxKey key) {
        pending.remove(Objects.requireNonNull(key, "key"));
    }

    @Override
    public synchronized EventInboxFailureResult markAttemptFailed(
            EventInboxKey key,
            Throwable failure,
            Instant failedAt,
            Instant nextAttemptAt,
            int maxAttempts
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        AtomicReference<EventInboxFailureResult> result =
                new AtomicReference<>(EventInboxFailureResult.ENTRY_NOT_FOUND);
        pending.computeIfPresent(key, (ignored, entry) -> {
            int attempts = entry.attempts() + 1;
            String message = failureMessage(failure);
            if (attempts >= maxAttempts) {
                deadLetters.put(key, new DeadLetteredEventInboxEntry(
                        key, entry.event(), entry.receivedAt(), attempts, failedAt, message
                ));
                result.set(EventInboxFailureResult.DEAD_LETTERED);
                return null;
            }
            result.set(EventInboxFailureResult.RETRY_SCHEDULED);
            return new PendingEventInboxEntry(
                    key, entry.event(), entry.receivedAt(), attempts, nextAttemptAt, message
            );
        });
        return result.get();
    }

    @Override
    public synchronized boolean redriveDeadLetter(EventInboxKey key, Instant nextAttemptAt) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        DeadLetteredEventInboxEntry entry = deadLetters.remove(key);
        if (entry == null) {
            return false;
        }
        pending.put(key, new PendingEventInboxEntry(
                key, entry.event(), entry.receivedAt(), 0, nextAttemptAt, ""
        ));
        return true;
    }

    private boolean isEarliest(PendingEventInboxEntry candidate) {
        return pending.keySet().stream()
                .filter(key -> key.topic().equals(candidate.key().topic()))
                .filter(key -> key.ownerKey().equals(candidate.key().ownerKey()))
                .noneMatch(key -> key.revision() < candidate.key().revision());
    }

    private boolean blocked(String topic, String ownerKey) {
        return deadLetters.keySet().stream()
                .anyMatch(key -> key.topic().equals(topic) && key.ownerKey().equals(ownerKey));
    }

    private static Comparator<PendingEventInboxEntry> entryOrder() {
        return Comparator.comparing(PendingEventInboxEntry::receivedAt)
                .thenComparing(entry -> entry.key().ownerKey())
                .thenComparingLong(entry -> entry.key().revision());
    }

    private static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }
}
