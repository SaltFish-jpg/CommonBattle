package com.commonbattle.game.social;

import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.event.ReliableSnapshotEventPublisher;
import com.commonbattle.game.event.SnapshotEventPublisher;
import com.commonbattle.game.event.VersionedEventOutbox;

import java.util.Objects;

/**
 * 联盟成员可靠事件发布器。
 * 联盟 owner 修改成员列表后，通过它保存最新成员快照并可靠发布成员变更事件。
 */
public final class ReliableAllianceMemberEventPublisher
        implements SnapshotEventPublisher<AllianceSnapshot, AllianceMemberChangedEvent> {
    private final ReliableSnapshotEventPublisher<AllianceSnapshot, AllianceMemberChangedEvent> publisher;

    public ReliableAllianceMemberEventPublisher(
            AllianceSnapshotRepository snapshots,
            VersionedEventOutbox outbox,
            EventPublisher delegate
    ) {
        Objects.requireNonNull(snapshots, "snapshots");
        this.publisher = new ReliableSnapshotEventPublisher<>(
                AllianceMemberChangedEvent.class,
                snapshots::save,
                outbox,
                delegate
        );
    }

    @Override
    public void publish(AllianceSnapshot snapshot, AllianceMemberChangedEvent event) {
        publisher.publish(snapshot, event);
    }

    public void replayPending() {
        publisher.replayPending();
    }
}
