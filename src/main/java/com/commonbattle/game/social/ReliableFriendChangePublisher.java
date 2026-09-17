package com.commonbattle.game.social;

import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.event.ReliableSnapshotEventPublisher;
import com.commonbattle.game.event.SnapshotEventPublisher;
import com.commonbattle.game.event.VersionedEventOutbox;

import java.util.Objects;

/**
 * 好友关系可靠事件发布器。
 * 它把好友快照和 FriendChangedEvent 统一交给通用快照发布器，确保回源快照先于跨服事件可见。
 */
public final class ReliableFriendChangePublisher implements SnapshotEventPublisher<FriendSnapshot, FriendChangedEvent> {
    private final ReliableSnapshotEventPublisher<FriendSnapshot, FriendChangedEvent> publisher;

    public ReliableFriendChangePublisher(
            FriendSnapshotRepository snapshots,
            VersionedEventOutbox outbox,
            EventPublisher delegate
    ) {
        this(snapshots, FriendSummaryListener.noop(), outbox, delegate);
    }

    public ReliableFriendChangePublisher(
            FriendSnapshotRepository snapshots,
            FriendSummaryListener summaryListener,
            VersionedEventOutbox outbox,
            EventPublisher delegate
    ) {
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(summaryListener, "summaryListener");
        this.publisher = new ReliableSnapshotEventPublisher<>(
                FriendChangedEvent.class,
                snapshots::save,
                summaryListener::onFriendSnapshot,
                outbox,
                delegate
        );
    }

    @Override
    public void publish(FriendSnapshot snapshot, FriendChangedEvent event) {
        publisher.publish(snapshot, event);
    }

    public void replayPending() {
        publisher.replayPending();
    }
}
