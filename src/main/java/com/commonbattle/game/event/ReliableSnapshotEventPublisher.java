package com.commonbattle.game.event;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 通用可靠快照事件发布器。
 * 它统一执行“保存最新快照 -> 写 outbox -> 尝试发布事件”的顺序，避免各业务 Agent 重复手写边界。
 */
public final class ReliableSnapshotEventPublisher<S, E extends VersionedEvent> implements SnapshotEventPublisher<S, E> {
    private final Class<E> eventType;
    private final Consumer<S> snapshotWriter;
    private final Consumer<S> afterSnapshotSaved;
    private final ReliableVersionedEventPublisher reliablePublisher;

    public ReliableSnapshotEventPublisher(
            Class<E> eventType,
            Consumer<S> snapshotWriter,
            VersionedEventOutbox outbox,
            EventPublisher delegate
    ) {
        this(eventType, snapshotWriter, ignored -> {
        }, outbox, delegate);
    }

    public ReliableSnapshotEventPublisher(
            Class<E> eventType,
            Consumer<S> snapshotWriter,
            Consumer<S> afterSnapshotSaved,
            VersionedEventOutbox outbox,
            EventPublisher delegate
    ) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.snapshotWriter = Objects.requireNonNull(snapshotWriter, "snapshotWriter");
        this.afterSnapshotSaved = Objects.requireNonNull(afterSnapshotSaved, "afterSnapshotSaved");
        this.reliablePublisher = new ReliableVersionedEventPublisher(outbox, delegate);
    }

    @Override
    public void publish(S snapshot, E event) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(event, "event");
        if (!eventType.isInstance(event)) {
            throw new IllegalArgumentException("event type must be " + eventType.getSimpleName());
        }
        snapshotWriter.accept(snapshot);
        afterSnapshotSaved.accept(snapshot);
        reliablePublisher.publish(event);
    }

    public void replayPending() {
        reliablePublisher.replayPending();
    }
}
