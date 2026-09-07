package com.commonbattle.example.cross.scene;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.game.scene.SceneRuntimeStats;
import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;

import java.util.Objects;
import java.util.Optional;

/**
 * 带 Profile 动态关注的 Scene 服务包装层。
 * 承载策略仍由 MultiSmallSceneService 或 LargeSceneShardService 决定，本类只把玩家进入/离开映射到资料关注生命周期。
 */
public final class ProfileAwareSceneService implements SceneServiceStrategy {
    private final SceneServiceStrategy delegate;
    private final SceneProfileAwarenessAgent profiles;
    private final ScenePlayerDomainEventAgent domainEvents;

    public ProfileAwareSceneService(SceneServiceStrategy delegate, SceneProfileAwarenessAgent profiles) {
        this(delegate, profiles, null);
    }

    public ProfileAwareSceneService(
            SceneServiceStrategy delegate,
            SceneProfileAwarenessAgent profiles,
            ScenePlayerDomainEventAgent domainEvents
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.domainEvents = domainEvents;
    }

    @Override
    public ServiceDescriptor descriptor() {
        return delegate.descriptor();
    }

    @Override
    public SceneRuntimeStats stats() {
        return delegate.stats();
    }

    @Override
    public ScenePlacement place(String sceneId, int chunkX, int chunkY) {
        return delegate.place(sceneId, chunkX, chunkY);
    }

    @Override
    public ScenePlacement enter(long playerId, String sceneId, int chunkX, int chunkY) {
        ScenePlacement placement = delegate.enter(playerId, sceneId, chunkX, chunkY);
        profiles.enter(playerId, placement.sceneId());
        if (domainEvents != null) {
            domainEvents.enter(playerId);
        }
        return placement;
    }

    @Override
    public boolean leave(long playerId, String sceneId) {
        boolean left = delegate.leave(playerId, sceneId);
        if (left) {
            profiles.leave(playerId, sceneId);
            if (domainEvents != null) {
                domainEvents.leave(playerId);
            }
        }
        return left;
    }

    public SceneProfileAwarenessAgent profiles() {
        return profiles;
    }

    public Optional<ScenePlayerDomainEventAgent> domainEvents() {
        return Optional.ofNullable(domainEvents);
    }
}
