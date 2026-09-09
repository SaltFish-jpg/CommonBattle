package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.event.SubscriptionCheckpoint;
import com.commonbattle.game.event.SubscriptionDecision;
import com.commonbattle.game.social.FriendChangedEvent;
import com.commonbattle.game.social.FriendOwnerKeyParser;
import com.commonbattle.game.social.FriendRelationAction;
import com.commonbattle.game.social.FriendSnapshot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 场景好友关系感知 Agent。
 * 场景只为在线玩家维护好友只读快照，用于可见性、同场景好友提示、聊天过滤等低延迟判断。
 */
public final class SceneFriendAwarenessAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private OwnerEventInterestControl interests;
    private final Set<Long> onlinePlayers = new HashSet<>();
    private final Map<Long, ScenePlayerFriendView> friends = new HashMap<>();
    private final SubscriptionCheckpoint checkpoint = new SubscriptionCheckpoint();

    public SceneFriendAwarenessAgent(AgentMessagePort messages, ActorRef self) {
        this(messages, self, OwnerEventInterestControl.noop());
    }

    public SceneFriendAwarenessAgent(
            AgentMessagePort messages,
            ActorRef self,
            OwnerEventInterestControl interests
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.self = Objects.requireNonNull(self, "self");
        this.interests = Objects.requireNonNull(interests, "interests");
    }

    public void attachInterests(OwnerEventInterestControl interests) {
        this.interests = Objects.requireNonNull(interests, "interests");
    }

    public void enter(long playerId) {
        messages.tellLocal(self, ignored -> {
            if (onlinePlayers.add(playerId)) {
                interests.watchOwner(FriendOwnerKeyParser.ownerKey(playerId));
            }
        });
    }

    public void leave(long playerId) {
        messages.tellLocal(self, ignored -> {
            if (onlinePlayers.remove(playerId)) {
                friends.remove(playerId);
                interests.unwatchOwner(FriendOwnerKeyParser.ownerKey(playerId));
            }
        });
    }

    public void onFriendChanged(FriendChangedEvent event) {
        messages.tellLocal(self, ignored -> handleFriendChanged(event));
    }

    public void handleFriendChanged(FriendChangedEvent event) {
        Objects.requireNonNull(event, "event");
        apply(event);
    }

    public void refresh(FriendSnapshot snapshot) {
        messages.tellLocal(self, ignored -> handleSnapshot(snapshot));
    }

    public void handleSnapshot(FriendSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!onlinePlayers.contains(snapshot.playerId())) {
            return;
        }
        if (snapshot.revision() < revisionOf(snapshot.playerId())) {
            return;
        }
        friends.put(snapshot.playerId(), new ScenePlayerFriendView(
                snapshot.playerId(),
                snapshot.revision(),
                snapshot.friends(),
                false
        ));
        checkpoint.reset(snapshot.ownerKey(), snapshot.revision());
    }

    public Optional<ScenePlayerFriendView> friendsOf(long playerId) {
        return Optional.ofNullable(friends.get(playerId));
    }

    public boolean stale(long playerId) {
        return friendsOf(playerId).map(ScenePlayerFriendView::stale).orElse(false);
    }

    public long revisionOf(long playerId) {
        return checkpoint.revisionOf(FriendOwnerKeyParser.ownerKey(playerId));
    }

    private void apply(FriendChangedEvent event) {
        SubscriptionDecision decision = checkpoint.inspect(event);
        if (decision == SubscriptionDecision.DUPLICATE_OR_OLD) {
            return;
        }
        if (decision == SubscriptionDecision.GAP) {
            interests.requestRepairOwner(event.ownerKey());
        }
        if (!onlinePlayers.contains(event.playerId())) {
            checkpoint.markApplied(event);
            return;
        }
        ScenePlayerFriendView current = friends.get(event.playerId());
        Set<Long> nextFriends = new HashSet<>(current == null ? Set.of() : current.friends());
        if (event.action() == FriendRelationAction.ADD) {
            nextFriends.add(event.friendId());
        } else {
            nextFriends.remove(event.friendId());
        }
        friends.put(event.playerId(), new ScenePlayerFriendView(
                event.playerId(),
                event.revision(),
                nextFriends,
                decision == SubscriptionDecision.GAP
        ));
        checkpoint.markApplied(event);
    }
}
