package com.commonbattle.game.profile;

import com.commonbattle.game.player.PlayerStateSnapshot;

import java.util.Objects;

/**
 * 玩家完整状态到基础资料快照的投影器。
 * 它只同步跨服通用展示字段，资产、背包、任务等强一致状态仍以玩家 owner 快照为准。
 */
public final class PlayerProfileSnapshotProjector {
    private final ProfileSnapshotRepository snapshots;

    public PlayerProfileSnapshotProjector(ProfileSnapshotRepository snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    public PlayerProfileSnapshot project(PlayerStateSnapshot state) {
        Objects.requireNonNull(state, "state");
        PlayerProfileSnapshot current = snapshots.find(state.playerId()).orElse(null);
        int level = state.growth().level();
        PlayerProfileSnapshot projected = new PlayerProfileSnapshot(
                state.playerId(),
                current == null ? defaultName(state.playerId()) : current.name(),
                level,
                current == null ? AppearanceSummary.defaults() : current.appearance(),
                current == null ? AllianceBrief.none() : current.alliance(),
                current == null ? new FriendBrief() : current.friends(),
                nextRevision(current, state, level),
                state.savedAt()
        );
        snapshots.save(projected);
        return projected;
    }

    private long nextRevision(PlayerProfileSnapshot current, PlayerStateSnapshot state, int level) {
        if (current == null) {
            return Math.max(1, state.revision());
        }
        if (current.level() == level) {
            return Math.max(current.revision(), state.revision());
        }
        return Math.max(current.revision() + 1, state.revision());
    }

    private String defaultName(long playerId) {
        return "player-" + playerId;
    }
}
