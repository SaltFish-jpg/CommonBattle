package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.profile.CachedProfile;
import com.commonbattle.game.profile.LocalProfileCache;
import com.commonbattle.game.profile.ProfileChangedEvent;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 场景内玩家基础资料感知 Agent。
 * 场景只为在线玩家维护本地 profile cache，头顶外观、联盟名、昵称等展示从这里读取。
 */
public final class SceneProfileAwarenessAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private final LocalProfileCache cache = new LocalProfileCache();
    private final Set<Long> onlinePlayers = new HashSet<>();

    public SceneProfileAwarenessAgent(AgentMessagePort messages, ActorRef self) {
        this.messages = messages;
        this.self = self;
    }

    public void enter(long playerId) {
        messages.tellLocal(self, ignored -> onlinePlayers.add(playerId));
    }

    public void leave(long playerId) {
        messages.tellLocal(self, ignored -> onlinePlayers.remove(playerId));
    }

    public void onProfileChanged(ProfileChangedEvent event) {
        messages.tellLocal(self, ignored -> {
            if (onlinePlayers.contains(event.playerId())) {
                cache.apply(event);
            }
        });
    }

    public Optional<CachedProfile> profileOf(long playerId) {
        return cache.get(playerId);
    }
}
