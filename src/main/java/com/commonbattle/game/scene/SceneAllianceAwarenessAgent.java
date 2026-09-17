package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.event.SubscriptionCheckpoint;
import com.commonbattle.game.event.SubscriptionDecision;
import com.commonbattle.game.social.AllianceOwnerKeyParser;
import com.commonbattle.game.social.AllianceMemberAction;
import com.commonbattle.game.social.AllianceMemberChangedEvent;
import com.commonbattle.game.social.AllianceSnapshot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 场景联盟感知 Agent。
 * 场景只维护在线玩家所需的联盟快照；事件跳号时标记快照脏，后续关键操作应回联盟 owner 校验。
 */
public final class SceneAllianceAwarenessAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private OwnerEventInterestControl interests;
    private AllianceMembershipChangeListener membershipChanges = AllianceMembershipChangeListener.noop();
    private final Set<Long> onlinePlayers = new HashSet<>();
    private final Map<Long, Integer> watchedAlliances = new HashMap<>();
    private final Map<Long, AllianceSnapshot> allianceSnapshots = new HashMap<>();
    private final Map<Long, ScenePlayerAllianceView> alliances = new HashMap<>();
    private final SubscriptionCheckpoint checkpoint = new SubscriptionCheckpoint();
    private long receivedEvents;
    private long appliedEvents;
    private long duplicateEvents;
    private long gapEvents;
    private long repairRequests;
    private long appliedSnapshots;
    private long ignoredSnapshots;

    public SceneAllianceAwarenessAgent(AgentMessagePort messages, ActorRef self) {
        this(messages, self, OwnerEventInterestControl.noop());
    }

    public SceneAllianceAwarenessAgent(
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

    public void attachMembershipChanges(AllianceMembershipChangeListener membershipChanges) {
        this.membershipChanges = Objects.requireNonNull(membershipChanges, "membershipChanges");
    }

    public void enter(long playerId) {
        messages.tellLocal(self, ignored -> {
            synchronized (this) {
                if (!onlinePlayers.add(playerId)) {
                    return;
                }
                for (AllianceSnapshot snapshot : allianceSnapshots.values()) {
                    if (snapshot.members().contains(playerId)) {
                        alliances.put(playerId, new ScenePlayerAllianceView(
                                playerId,
                                snapshot.allianceId(),
                                snapshot.revision(),
                                false
                        ));
                        return;
                    }
                }
            }
        });
    }

    public void leave(long playerId) {
        messages.tellLocal(self, ignored -> {
            synchronized (this) {
                onlinePlayers.remove(playerId);
                alliances.remove(playerId);
            }
        });
    }

    public void watchAlliance(long allianceId) {
        messages.tellLocal(self, ignored -> {
            synchronized (this) {
                int references = watchedAlliances.getOrDefault(allianceId, 0);
                watchedAlliances.put(allianceId, references + 1);
                if (references == 0) {
                    interests.watchOwner(AllianceOwnerKeyParser.ownerKey(allianceId));
                }
            }
        });
    }

    public void unwatchAlliance(long allianceId) {
        messages.tellLocal(self, ignored -> {
            synchronized (this) {
                int references = watchedAlliances.getOrDefault(allianceId, 0);
                if (references <= 0) {
                    return;
                }
                if (references == 1) {
                    watchedAlliances.remove(allianceId);
                    allianceSnapshots.remove(allianceId);
                    checkpoint.remove(AllianceOwnerKeyParser.ownerKey(allianceId));
                    alliances.entrySet().removeIf(entry -> entry.getValue().allianceId() == allianceId);
                    interests.unwatchOwner(AllianceOwnerKeyParser.ownerKey(allianceId));
                    return;
                }
                watchedAlliances.put(allianceId, references - 1);
            }
        });
    }

    public void onAllianceChanged(AllianceMemberChangedEvent event) {
        messages.tellLocal(self, ignored -> handleAllianceChanged(event));
    }

    public synchronized void handleAllianceChanged(AllianceMemberChangedEvent event) {
        apply(event);
    }

    public void refresh(AllianceSnapshot snapshot) {
        messages.tellLocal(self, ignored -> handleSnapshot(snapshot));
    }

    public synchronized void handleSnapshot(AllianceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!watchedAlliances.containsKey(snapshot.allianceId())) {
            ignoredSnapshots++;
            return;
        }
        if (snapshot.revision() < revisionOf(snapshot.allianceId())) {
            ignoredSnapshots++;
            return;
        }
        appliedSnapshots++;
        allianceSnapshots.put(snapshot.allianceId(), snapshot);
        for (long playerId : onlinePlayers) {
            ScenePlayerAllianceView current = alliances.get(playerId);
            if (snapshot.members().contains(playerId)) {
                alliances.put(playerId, new ScenePlayerAllianceView(
                        playerId,
                        snapshot.allianceId(),
                        snapshot.revision(),
                        false
                ));
            } else if (current != null && current.allianceId() == snapshot.allianceId()) {
                alliances.remove(playerId);
            }
        }
        checkpoint.reset(snapshot.ownerKey(), snapshot.revision());
        membershipChanges.onAllianceSnapshot(snapshot);
    }

    public synchronized Optional<ScenePlayerAllianceView> allianceOf(long playerId) {
        return Optional.ofNullable(alliances.get(playerId));
    }

    public synchronized AllianceMembershipDecision membershipOf(long allianceId, long playerId) {
        ScenePlayerAllianceView view = alliances.get(playerId);
        if (view != null && view.allianceId() == allianceId) {
            return view.stale() ? AllianceMembershipDecision.STALE : AllianceMembershipDecision.MEMBER;
        }
        AllianceSnapshot snapshot = allianceSnapshots.get(allianceId);
        if (snapshot == null || snapshot.revision() < checkpoint.revisionOf(AllianceOwnerKeyParser.ownerKey(allianceId))) {
            return AllianceMembershipDecision.STALE;
        }
        return snapshot.members().contains(playerId)
                ? AllianceMembershipDecision.MEMBER
                : AllianceMembershipDecision.NOT_MEMBER;
    }

    public synchronized long revisionOf(long allianceId) {
        return checkpoint.revisionOf("alliance:" + allianceId);
    }

    public synchronized SceneProjectionStats projectionStats() {
        int staleViews = 0;
        for (ScenePlayerAllianceView view : alliances.values()) {
            if (view.stale()) {
                staleViews++;
            }
        }
        return new SceneProjectionStats(
                receivedEvents,
                appliedEvents,
                duplicateEvents,
                gapEvents,
                repairRequests,
                appliedSnapshots,
                ignoredSnapshots,
                staleViews
        );
    }

    private void apply(AllianceMemberChangedEvent event) {
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
            applySnapshotEvent(event, decision);
            checkpoint.markApplied(event);
            appliedEvents++;
            return;
        }
        if (decision == SubscriptionDecision.GAP) {
            alliances.put(event.playerId(), new ScenePlayerAllianceView(
                    event.playerId(),
                    event.allianceId(),
                    event.revision(),
                    true
            ));
            checkpoint.markApplied(event);
            appliedEvents++;
            return;
        }
        // 场景感知事件边界：只更新本场景在线玩家快照，revision 去重后再改变可见状态。
        if (event.action() == AllianceMemberAction.JOIN) {
            alliances.put(event.playerId(), new ScenePlayerAllianceView(
                    event.playerId(),
                    event.allianceId(),
                    event.revision(),
                    false
            ));
        } else {
            alliances.remove(event.playerId());
            membershipChanges.onAllianceMemberLeft(event.allianceId(), event.playerId(), event.revision());
        }
        applySnapshotEvent(event, decision);
        checkpoint.markApplied(event);
        appliedEvents++;
    }

    private void applySnapshotEvent(AllianceMemberChangedEvent event, SubscriptionDecision decision) {
        AllianceSnapshot current = allianceSnapshots.get(event.allianceId());
        if (current == null || decision == SubscriptionDecision.GAP) {
            return;
        }
        Set<Long> members = new HashSet<>(current.members());
        if (event.action() == AllianceMemberAction.JOIN) {
            members.add(event.playerId());
        } else {
            members.remove(event.playerId());
        }
        allianceSnapshots.put(event.allianceId(), new AllianceSnapshot(event.allianceId(), event.revision(), members));
    }
}
