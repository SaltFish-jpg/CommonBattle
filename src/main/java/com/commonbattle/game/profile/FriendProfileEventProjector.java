package com.commonbattle.game.profile;

import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.social.FriendSnapshot;
import com.commonbattle.game.social.FriendSummaryListener;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;

/**
 * 好友关系到玩家基础资料的投影器。
 * 它把完整好友列表压缩为 FriendBrief 摘要，供 Scene、Chat 等服务通过 Profile cache 快速读取。
 */
public final class FriendProfileEventProjector implements FriendSummaryListener {
    private final ProfileSnapshotRepository snapshots;
    private final EventPublisher publisher;
    private final Clock clock;

    public FriendProfileEventProjector(
            ProfileSnapshotRepository snapshots,
            EventPublisher publisher,
            Clock clock
    ) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onFriendSnapshot(FriendSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        PlayerProfileSnapshot current = snapshots.find(snapshot.playerId()).orElse(null);
        FriendBrief nextFriends = new FriendBrief(snapshot.friends().size(), snapshot.revision());
        PlayerProfileSnapshot next = new PlayerProfileSnapshot(
                snapshot.playerId(),
                current == null ? defaultName(snapshot.playerId()) : current.name(),
                current == null ? 1 : current.level(),
                current == null ? AppearanceSummary.defaults() : current.appearance(),
                current == null ? AllianceBrief.none() : current.alliance(),
                nextFriends,
                nextRevision(current, snapshot),
                clock.instant()
        );
        snapshots.save(next);
        publisher.publish(new ProfileChangedEvent(snapshot.playerId(), Set.of(ProfileField.FRIENDS), next));
    }

    private long nextRevision(PlayerProfileSnapshot current, FriendSnapshot snapshot) {
        if (current == null) {
            return Math.max(1, snapshot.revision());
        }
        return Math.max(current.revision() + 1, snapshot.revision());
    }

    private String defaultName(long playerId) {
        return "player-" + playerId;
    }
}
