package com.commonbattle.game.player;

import com.commonbattle.game.activity.PlayerActivities;
import com.commonbattle.game.activity.ActivityParticipant;
import com.commonbattle.game.achievement.PlayerAchievements;
import com.commonbattle.game.bag.PlayerBag;
import com.commonbattle.game.battle.PlayerBattleState;
import com.commonbattle.game.growth.GrowthProfile;
import com.commonbattle.game.shop.PlayerShopState;
import com.commonbattle.game.task.PlayerTasks;

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
    private final PlayerShopState shop = new PlayerShopState();
    private final PlayerBattleState battle = new PlayerBattleState();
    private final PlayerTasks tasks = new PlayerTasks();
    private final PlayerAchievements achievements = new PlayerAchievements();

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

    public PlayerShopState shop() {
        return shop;
    }

    public PlayerBattleState battle() {
        return battle;
    }

    public PlayerTasks tasks() {
        return tasks;
    }

    public PlayerAchievements achievements() {
        return achievements;
    }

    public PlayerStateSnapshot snapshot(long revision, Instant savedAt) {
        return snapshot(revision, 0, savedAt);
    }

    public PlayerStateSnapshot snapshot(long revision, long eventRevision, Instant savedAt) {
        return new PlayerStateSnapshot(
                playerId,
                createdAt,
                bag.snapshot(),
                activities.snapshot(),
                growth.snapshot(),
                shop.snapshot(),
                battle.snapshot(),
                tasks.snapshot(),
                achievements.snapshot(),
                eventRevision,
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
        profile.shop.restore(snapshot.shop());
        profile.battle.restore(snapshot.battle());
        profile.tasks.restore(snapshot.tasks());
        profile.achievements.restore(snapshot.achievements());
        return profile;
    }
}
