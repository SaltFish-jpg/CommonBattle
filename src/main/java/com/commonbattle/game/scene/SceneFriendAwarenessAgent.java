package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.event.SubscriptionCheckpoint;
import com.commonbattle.game.event.SubscriptionDecision;
import com.commonbattle.game.social.FriendChangedEvent;
import com.commonbattle.game.social.FriendOwnerKeyParser;
import com.commonbattle.game.social.FriendRelationAction;
import com.commonbattle.game.social.FriendSnapshot;
import com.commonbattle.game.social.FriendSnapshotReader;
import com.commonbattle.game.snapshot.SnapshotReadMetrics;
import com.commonbattle.game.snapshot.SnapshotReadStats;
import com.commonbattle.game.snapshot.SnapshotReadStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 场景好友关系感知 Agent。
 * 场景只为在线玩家维护好友只读快照，用于可见性、同场景好友提示、聊天过滤等低延迟判断。
 */
public final class SceneFriendAwarenessAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private final FriendSnapshotReader snapshotReader;
    private OwnerEventInterestControl interests;
    private final Set<Long> onlinePlayers = new HashSet<>();
    private final Map<Long, ScenePlayerFriendView> friends = new HashMap<>();
    private final SubscriptionCheckpoint checkpoint = new SubscriptionCheckpoint();
    private long receivedEvents;
    private long appliedEvents;
    private long duplicateEvents;
    private long gapEvents;
    private long repairRequests;
    private long appliedSnapshots;
    private long ignoredSnapshots;
    private final SnapshotReadMetrics freshReads = new SnapshotReadMetrics();

    public SceneFriendAwarenessAgent(AgentMessagePort messages, ActorRef self) {
        this(messages, self, OwnerEventInterestControl.noop());
    }

    public SceneFriendAwarenessAgent(
            AgentMessagePort messages,
            ActorRef self,
            OwnerEventInterestControl interests
    ) {
        this(messages, self, interests, ignored -> Optional.empty());
    }

    public SceneFriendAwarenessAgent(
            AgentMessagePort messages,
            ActorRef self,
            OwnerEventInterestControl interests,
            FriendSnapshotReader snapshotReader
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.self = Objects.requireNonNull(self, "self");
        this.interests = Objects.requireNonNull(interests, "interests");
        this.snapshotReader = Objects.requireNonNull(snapshotReader, "snapshotReader");
    }

    public void attachInterests(OwnerEventInterestControl interests) {
        this.interests = Objects.requireNonNull(interests, "interests");
    }

    public void enter(long playerId) {
        messages.tellLocal(self, ignored -> {
            synchronized (this) {
                if (onlinePlayers.add(playerId)) {
                    interests.watchOwner(FriendOwnerKeyParser.ownerKey(playerId));
                }
            }
        });
    }

    public void leave(long playerId) {
        messages.tellLocal(self, ignored -> {
            synchronized (this) {
                if (onlinePlayers.remove(playerId)) {
                    friends.remove(playerId);
                    checkpoint.remove(FriendOwnerKeyParser.ownerKey(playerId));
                    interests.unwatchOwner(FriendOwnerKeyParser.ownerKey(playerId));
                }
            }
        });
    }

    public void onFriendChanged(FriendChangedEvent event) {
        messages.tellLocal(self, ignored -> handleFriendChanged(event));
    }

    public void onFriendChangedAndReadView(
            FriendChangedEvent event,
            Consumer<Optional<ScenePlayerFriendView>> callback
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(callback, "callback");
        messages.tellLocal(self, ignored -> {
            handleFriendChanged(event);
            callback.accept(friendsOf(event.playerId()));
        });
    }

    public void onFriendChangedAndReadFreshView(
            FriendChangedEvent event,
            Consumer<Optional<ScenePlayerFriendView>> callback
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(callback, "callback");
        messages.tellLocal(self, ignored -> {
            handleFriendChanged(event);
            callback.accept(readFreshFriendsOf(event.playerId(), event.revision()).view());
        });
    }

    public void onFriendChangedAndReadFreshResult(
            FriendChangedEvent event,
            Consumer<SceneFriendReadResult> callback
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(callback, "callback");
        messages.tellLocal(self, ignored -> {
            handleFriendChanged(event);
            callback.accept(readFreshFriendsOf(event.playerId(), event.revision()));
        });
    }

    public synchronized void handleFriendChanged(FriendChangedEvent event) {
        ActorContext.requireCurrent(self);
        Objects.requireNonNull(event, "event");
        apply(event);
    }

    public void refresh(FriendSnapshot snapshot) {
        messages.tellLocal(self, ignored -> handleSnapshot(snapshot));
    }

    public synchronized void handleSnapshot(FriendSnapshot snapshot) {
        ActorContext.requireCurrent(self);
        Objects.requireNonNull(snapshot, "snapshot");
        if (!onlinePlayers.contains(snapshot.playerId())) {
            ignoredSnapshots++;
            return;
        }
        if (snapshot.revision() < revisionOf(snapshot.playerId())) {
            ignoredSnapshots++;
            return;
        }
        appliedSnapshots++;
        friends.put(snapshot.playerId(), new ScenePlayerFriendView(
                snapshot.playerId(),
                snapshot.revision(),
                snapshot.friends(),
                false
        ));
        checkpoint.reset(snapshot.ownerKey(), snapshot.revision());
    }

    public synchronized Optional<ScenePlayerFriendView> friendsOf(long playerId) {
        return Optional.ofNullable(friends.get(playerId));
    }

    public synchronized Optional<ScenePlayerFriendView> freshFriendsOf(long playerId, long minimumRevision) {
        ActorContext.requireCurrent(self);
        SceneFriendReadResult result = readFreshFriendsOf(playerId, minimumRevision);
        return result.fresh() ? result.view() : Optional.empty();
    }

    public synchronized SceneFriendReadResult readFreshFriendsOf(long playerId, long minimumRevision) {
        ActorContext.requireCurrent(self);
        if (minimumRevision < 0) {
            throw new IllegalArgumentException("minimumRevision must not be negative");
        }
        freshReads.request();
        Optional<ScenePlayerFriendView> local = friendsOf(playerId)
                .filter(view -> !view.stale() && view.revision() >= minimumRevision);
        if (local.isPresent()) {
            freshReads.record(SnapshotReadStatus.LOCAL_HIT);
            return SceneFriendReadResult.of(local.orElseThrow(), SceneFriendReadStatus.LOCAL_HIT);
        }
        Optional<FriendSnapshot> snapshot = snapshotReader.find(playerId);
        if (snapshot.isPresent() && snapshot.orElseThrow().revision() >= minimumRevision) {
            handleSnapshot(snapshot.orElseThrow());
            Optional<ScenePlayerFriendView> refreshed = friendsOf(playerId)
                    .filter(view -> !view.stale() && view.revision() >= minimumRevision);
            if (refreshed.isPresent()) {
                freshReads.record(SnapshotReadStatus.REFRESHED);
                return SceneFriendReadResult.of(refreshed.orElseThrow(), SceneFriendReadStatus.REFRESHED);
            }
            freshReads.record(SnapshotReadStatus.REMOTE_STALE);
            return SceneFriendReadResult.empty(SceneFriendReadStatus.REMOTE_STALE);
        }
        if (snapshot.isPresent()) {
            freshReads.record(SnapshotReadStatus.REMOTE_STALE);
            return SceneFriendReadResult.empty(SceneFriendReadStatus.REMOTE_STALE);
        } else {
            freshReads.record(SnapshotReadStatus.REMOTE_MISS);
            return SceneFriendReadResult.empty(SceneFriendReadStatus.REMOTE_MISS);
        }
    }

    public synchronized boolean stale(long playerId) {
        return friendsOf(playerId).map(ScenePlayerFriendView::stale).orElse(false);
    }

    public synchronized long revisionOf(long playerId) {
        return checkpoint.revisionOf(FriendOwnerKeyParser.ownerKey(playerId));
    }

    public synchronized SceneProjectionStats projectionStats() {
        int staleViews = 0;
        for (ScenePlayerFriendView view : friends.values()) {
            if (view.stale()) {
                staleViews++;
            }
        }
        SnapshotReadStats readStats = freshReads.snapshot();
        return new SceneProjectionStats(
                receivedEvents,
                appliedEvents,
                duplicateEvents,
                gapEvents,
                repairRequests,
                appliedSnapshots,
                ignoredSnapshots,
                staleViews,
                readStats.requests(),
                readStats.localHits(),
                readStats.refreshes(),
                readStats.remoteStale(),
                readStats.remoteMisses()
        );
    }

    private void apply(FriendChangedEvent event) {
        receivedEvents++;
        SubscriptionDecision decision = checkpoint.inspect(event);
        if (decision == SubscriptionDecision.DUPLICATE_OR_OLD) {
            duplicateEvents++;
            return;
        }
        if (decision == SubscriptionDecision.GAP) {
            gapEvents++;
            repairRequests++;
            interests.requestRepairOwner(event.ownerKey());
        }
        if (!onlinePlayers.contains(event.playerId())) {
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
        appliedEvents++;
    }
}
