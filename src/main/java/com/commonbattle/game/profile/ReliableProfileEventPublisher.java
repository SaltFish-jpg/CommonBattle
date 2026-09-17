package com.commonbattle.game.profile;

import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.event.ReliableSnapshotEventPublisher;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.event.VersionedEventOutbox;

import java.util.Objects;

/**
 * 玩家资料可靠事件发布器。
 * 它把 ProfileChangedEvent 转成“保存最新快照 -> 写 outbox -> 尝试发布”的固定边界。
 */
public final class ReliableProfileEventPublisher implements EventPublisher {
    private final ReliableSnapshotEventPublisher<PlayerProfileSnapshot, ProfileChangedEvent> publisher;

    public ReliableProfileEventPublisher(
            ProfileSnapshotRepository snapshots,
            VersionedEventOutbox outbox,
            EventPublisher delegate
    ) {
        Objects.requireNonNull(snapshots, "snapshots");
        this.publisher = new ReliableSnapshotEventPublisher<>(
                ProfileChangedEvent.class,
                snapshots::save,
                outbox,
                delegate
        );
    }

    @Override
    public void publish(VersionedEvent event) {
        if (!(event instanceof ProfileChangedEvent profileEvent)) {
            throw new IllegalArgumentException("ReliableProfileEventPublisher only accepts ProfileChangedEvent");
        }
        publisher.publish(profileEvent.snapshot(), profileEvent);
    }

    public void replayPending() {
        publisher.replayPending();
    }
}
