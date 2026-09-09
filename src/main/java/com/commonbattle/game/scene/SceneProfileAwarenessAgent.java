package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.profile.CachedProfile;
import com.commonbattle.game.profile.LocalProfileCache;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.profile.ProfileReadMode;
import com.commonbattle.game.profile.ProfileReadResult;
import com.commonbattle.game.profile.ProfileRuntime;
import com.commonbattle.game.event.SubscriptionDecision;
import com.commonbattle.game.profile.PlayerProfileSnapshot;

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
    private final ProfileRuntime profiles;
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
        this(messages, self, new ProfileRuntime(cache, interests, ignored -> Optional.empty()));
    }

    public SceneProfileAwarenessAgent(
            AgentMessagePort messages,
            ActorRef self,
            ProfileRuntime profiles
    ) {
        this.messages = messages;
        this.self = self;
        this.profiles = Objects.requireNonNull(profiles, "profiles");
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
                profiles.watch(playerId);
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
            profiles.unwatch(playerId);
        });
    }

    public void onProfileChanged(ProfileChangedEvent event) {
        messages.tellLocal(self, ignored -> handleProfileChanged(event));
    }

    public void handleProfileChanged(ProfileChangedEvent event) {
        Objects.requireNonNull(event, "event");
        if (onlineInterests.containsKey(event.playerId())) {
            SubscriptionDecision decision = profiles.apply(event);
            if (decision == SubscriptionDecision.GAP) {
                profiles.requestRepair(event.playerId());
            }
        }
    }

    public void refresh(PlayerProfileSnapshot snapshot) {
        messages.tellLocal(self, ignored -> handleSnapshot(snapshot));
    }

    public void handleSnapshot(PlayerProfileSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!onlineInterests.containsKey(snapshot.playerId())) {
            return;
        }
        if (snapshot.revision() < profiles.revisionOf(snapshot.playerId())) {
            return;
        }
        profiles.refresh(snapshot);
    }

    public Optional<CachedProfile> profileOf(long playerId) {
        return profiles.read(playerId, ProfileReadMode.LOCAL_FAST).profile();
    }

    public Optional<CachedProfile> freshProfileOf(long playerId, long minimumRevision) {
        ProfileReadResult result = profiles.readAtLeast(playerId, minimumRevision);
        return result.fresh() ? result.profile() : Optional.empty();
    }

    public ProfileRuntime profileRuntime() {
        return profiles;
    }
}
