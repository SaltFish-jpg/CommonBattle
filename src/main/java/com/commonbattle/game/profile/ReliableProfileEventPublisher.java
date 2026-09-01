package com.commonbattle.game.profile;

import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.event.PendingVersionedEvent;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.event.VersionedEventOutbox;

import java.util.Objects;

/**
 * 玩家资料可靠事件发布器。
 * 它把 ProfileChangedEvent 转成“保存最新快照 -> 写 outbox -> 尝试发布”的固定边界。
 */
public final class ReliableProfileEventPublisher implements EventPublisher {
    private final ProfileSnapshotRepository snapshots;
    private final VersionedEventOutbox outbox;
    private final EventPublisher delegate;

    public ReliableProfileEventPublisher(
            ProfileSnapshotRepository snapshots,
            VersionedEventOutbox outbox,
            EventPublisher delegate
    ) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void publish(VersionedEvent event) {
        if (!(event instanceof ProfileChangedEvent profileEvent)) {
            throw new IllegalArgumentException("ReliableProfileEventPublisher only accepts ProfileChangedEvent");
        }
        snapshots.save(profileEvent.snapshot());
        PendingVersionedEvent entry = outbox.append(profileEvent);
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
