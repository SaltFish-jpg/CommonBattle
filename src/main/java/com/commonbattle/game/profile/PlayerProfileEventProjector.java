package com.commonbattle.game.profile;

import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.player.PlayerProfile;
import com.commonbattle.game.player.event.GrowthLevelChangedEvent;
import com.commonbattle.game.player.event.PlayerDomainEvent;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;

/**
 * 玩家领域事件到基础资料事件的投影器。
 * 它把等级等通用展示字段转换为 ProfileChangedEvent，订阅方可用同一套 profile cache 感知在线变化。
 */
public final class PlayerProfileEventProjector {
    private final ProfileSnapshotRepository snapshots;
    private final EventPublisher publisher;
    private final Clock clock;

    public PlayerProfileEventProjector(
            ProfileSnapshotRepository snapshots,
            EventPublisher publisher,
            Clock clock
    ) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void onPlayerDomainEvent(PlayerProfile profile, long eventRevision, PlayerDomainEvent event) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(event, "event");
        if (event instanceof GrowthLevelChangedEvent) {
            publishLevelChanged(profile, eventRevision);
        }
    }

    private void publishLevelChanged(PlayerProfile profile, long eventRevision) {
        PlayerProfileSnapshot current = snapshots.find(profile.playerId()).orElse(null);
        PlayerProfileSnapshot next = new PlayerProfileSnapshot(
                profile.playerId(),
                current == null ? defaultName(profile.playerId()) : current.name(),
                profile.level(),
                current == null ? AppearanceSummary.defaults() : current.appearance(),
                current == null ? AllianceBrief.none() : current.alliance(),
                current == null ? new FriendBrief() : current.friends(),
                nextRevision(current, eventRevision),
                clock.instant()
        );
        snapshots.save(next);
        publisher.publish(new ProfileChangedEvent(profile.playerId(), Set.of(ProfileField.LEVEL), next));
    }

    private long nextRevision(PlayerProfileSnapshot current, long eventRevision) {
        if (current == null) {
            return Math.max(1, eventRevision);
        }
        return Math.max(current.revision() + 1, eventRevision);
    }

    private String defaultName(long playerId) {
        return "player-" + playerId;
    }
}
