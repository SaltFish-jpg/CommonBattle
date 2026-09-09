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
    private final Set<Long> onlinePlayers = new HashSet<>();
    private final Map<Long, Integer> watchedAlliances = new HashMap<>();
    private final Map<Long, ScenePlayerAllianceView> alliances = new HashMap<>();
    private final SubscriptionCheckpoint checkpoint = new SubscriptionCheckpoint();

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

    public void enter(long playerId) {
        messages.tellLocal(self, ignored -> onlinePlayers.add(playerId));
    }

    public void leave(long playerId) {
        messages.tellLocal(self, ignored -> {
            onlinePlayers.remove(playerId);
            alliances.remove(playerId);
        });
    }

    public void watchAlliance(long allianceId) {
        messages.tellLocal(self, ignored -> {
            int references = watchedAlliances.getOrDefault(allianceId, 0);
            watchedAlliances.put(allianceId, references + 1);
            if (references == 0) {
                interests.watchOwner(AllianceOwnerKeyParser.ownerKey(allianceId));
            }
        });
    }

    public void unwatchAlliance(long allianceId) {
        messages.tellLocal(self, ignored -> {
            int references = watchedAlliances.getOrDefault(allianceId, 0);
            if (references <= 0) {
                return;
            }
            if (references == 1) {
                watchedAlliances.remove(allianceId);
                interests.unwatchOwner(AllianceOwnerKeyParser.ownerKey(allianceId));
                return;
            }
            watchedAlliances.put(allianceId, references - 1);
        });
    }

    public void onAllianceChanged(AllianceMemberChangedEvent event) {
        messages.tellLocal(self, ignored -> handleAllianceChanged(event));
    }

    public void handleAllianceChanged(AllianceMemberChangedEvent event) {
        apply(event);
    }

    public void refresh(AllianceSnapshot snapshot) {
        messages.tellLocal(self, ignored -> handleSnapshot(snapshot));
    }

    public void handleSnapshot(AllianceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!watchedAlliances.containsKey(snapshot.allianceId())) {
            return;
        }
        if (snapshot.revision() < revisionOf(snapshot.allianceId())) {
            return;
        }
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
    }

    public Optional<ScenePlayerAllianceView> allianceOf(long playerId) {
        return Optional.ofNullable(alliances.get(playerId));
    }

    public long revisionOf(long allianceId) {
        return checkpoint.revisionOf("alliance:" + allianceId);
    }

    private void apply(AllianceMemberChangedEvent event) {
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
        if (decision == SubscriptionDecision.GAP) {
            alliances.put(event.playerId(), new ScenePlayerAllianceView(
                    event.playerId(),
                    event.allianceId(),
                    event.revision(),
                    true
            ));
            checkpoint.markApplied(event);
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
        }
        checkpoint.markApplied(event);
    }
}
