package com.commonbattle.game.event;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版 outbox，适合单元测试和本地进程样例。
 * 生产环境应替换为与业务状态同事务写入的 DB outbox 表。
 */
public final class InMemoryVersionedEventOutbox implements VersionedEventOutbox {
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<Long, PendingVersionedEvent> pending = new ConcurrentHashMap<>();

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
    public void markPublished(long outboxId) {
        pending.remove(outboxId);
    }

    @Override
    public void markAttemptFailed(long outboxId) {
        pending.computeIfPresent(outboxId, (ignored, entry) ->
                new PendingVersionedEvent(entry.id(), entry.event(), entry.createdAt(), entry.attempts() + 1));
    }
}
