package com.commonbattle.game.event;

import java.util.Objects;

/**
 * 通用可靠版本事件发布器。
 * 状态 owner 先写 outbox，再尝试投递；投递失败保留 pending，后台可调用 replayPending 补发。
 */
public final class ReliableVersionedEventPublisher implements EventPublisher {
    private final VersionedEventOutbox outbox;
    private final EventPublisher delegate;

    public ReliableVersionedEventPublisher(VersionedEventOutbox outbox, EventPublisher delegate) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void publish(VersionedEvent event) {
        PendingVersionedEvent entry = outbox.append(Objects.requireNonNull(event, "event"));
        dispatch(entry);
    }

    public void replayPending() {
        outbox.pending().forEach(this::dispatch);
    }

    private void dispatch(PendingVersionedEvent entry) {
        try {
            delegate.publish(entry.event());
            outbox.markPublished(entry.id());
        } catch (RuntimeException e) {
            outbox.markAttemptFailed(entry.id());
        }
    }
}
