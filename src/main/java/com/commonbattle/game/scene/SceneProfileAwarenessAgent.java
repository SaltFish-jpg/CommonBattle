package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.profile.CachedProfile;
import com.commonbattle.game.profile.LocalProfileCache;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileInterestControl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 场景内玩家基础资料感知 Agent。
 * 场景只为在线玩家维护本地 profile cache，头顶外观、联盟名、昵称等展示从这里读取。
 */
public final class SceneProfileAwarenessAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private final ProfileInterestControl interests;
    private final LocalProfileCache cache;
    private final Map<Long, Set<String>> onlineInterests = new HashMap<>();

    public SceneProfileAwarenessAgent(AgentMessagePort messages, ActorRef self) {
        this(messages, self, ProfileInterestControl.noop());
    }

    public SceneProfileAwarenessAgent(AgentMessagePort messages, ActorRef self, ProfileInterestControl interests) {
        this(messages, self, interests, new LocalProfileCache());
    }

    public SceneProfileAwarenessAgent(
            AgentMessagePort messages,
            ActorRef self,
            ProfileInterestControl interests,
            LocalProfileCache cache
    ) {
        this.messages = messages;
        this.self = self;
        this.interests = Objects.requireNonNull(interests, "interests");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    public void enter(long playerId) {
        enter(playerId, "default");
    }

    public void enter(long playerId, String interestKey) {
        Objects.requireNonNull(interestKey, "interestKey");
        if (interestKey.isBlank()) {
            throw new IllegalArgumentException("interestKey must not be blank");
        }
        messages.tellLocal(self, ignored -> {
            Set<String> keys = onlineInterests.computeIfAbsent(playerId, ignoredPlayer -> new HashSet<>());
            if (keys.add(interestKey)) {
                interests.watch(playerId);
            }
        });
    }

    public void leave(long playerId) {
        leave(playerId, "default");
    }

    public void leave(long playerId, String interestKey) {
        Objects.requireNonNull(interestKey, "interestKey");
        if (interestKey.isBlank()) {
            throw new IllegalArgumentException("interestKey must not be blank");
        }
        messages.tellLocal(self, ignored -> {
            Set<String> keys = onlineInterests.get(playerId);
            if (keys == null || !keys.remove(interestKey)) {
                return;
            }
            if (keys.isEmpty()) {
                onlineInterests.remove(playerId);
            }
            interests.unwatch(playerId);
        });
    }

    public void onProfileChanged(ProfileChangedEvent event) {
        messages.tellLocal(self, ignored -> {
            if (onlineInterests.containsKey(event.playerId())) {
                cache.apply(event);
            }
        });
    }

    public Optional<CachedProfile> profileOf(long playerId) {
        return cache.get(playerId);
    }
}
