package com.commonbattle.game.player;

import com.commonbattle.game.activity.PlayerActivities;
import com.commonbattle.game.activity.ActivityParticipant;
import com.commonbattle.game.bag.PlayerBag;
import com.commonbattle.game.growth.GrowthProfile;

import java.time.Instant;
import java.util.Objects;

/**
 * 玩家通用业务状态聚合。
 * 该聚合对象只应在玩家 Agent 邮箱中被修改。
 */
public final class PlayerProfile implements ActivityParticipant {
    private final long playerId;
    private final Instant createdAt;
    private final PlayerBag bag = new PlayerBag();
    private final PlayerActivities activities = new PlayerActivities();
    private final GrowthProfile growth = new GrowthProfile();

    public PlayerProfile(long playerId) {
        this(playerId, Instant.EPOCH);
    }

    public PlayerProfile(long playerId, Instant createdAt) {
        this.playerId = playerId;
        this.createdAt = createdAt;
    }

    @Override
    public long playerId() {
        return playerId;
    }

    @Override
    public int level() {
        return growth.level();
    }

    @Override
    public Instant createdAt() {
        return createdAt;
    }

    public PlayerBag bag() {
        return bag;
    }

    public PlayerActivities activities() {
        return activities;
    }

    public GrowthProfile growth() {
        return growth;
    }

    public PlayerStateSnapshot snapshot(long revision, Instant savedAt) {
        return new PlayerStateSnapshot(
                playerId,
                createdAt,
                bag.snapshot(),
                activities.snapshot(),
                growth.snapshot(),
                revision,
                savedAt
        );
    }

    public static PlayerProfile restore(PlayerStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        PlayerProfile profile = new PlayerProfile(snapshot.playerId(), snapshot.createdAt());
        profile.bag.restore(snapshot.bag());
        profile.activities.restore(snapshot.activities());
        profile.growth.restore(snapshot.growth());
        return profile;
    }
}
