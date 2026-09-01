package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.event.SubscriptionCheckpoint;
import com.commonbattle.game.event.SubscriptionDecision;
import com.commonbattle.game.social.AllianceMemberAction;
import com.commonbattle.game.social.AllianceMemberChangedEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 场景联盟感知 Agent。
 * 场景只维护在线玩家所需的联盟快照；事件跳号时标记快照脏，后续关键操作应回联盟 owner 校验。
 */
public final class SceneAllianceAwarenessAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private final Set<Long> onlinePlayers = new HashSet<>();
    private final Map<Long, ScenePlayerAllianceView> alliances = new HashMap<>();
    private final SubscriptionCheckpoint checkpoint = new SubscriptionCheckpoint();

    public SceneAllianceAwarenessAgent(AgentMessagePort messages, ActorRef self) {
        this.messages = messages;
        this.self = self;
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

    public void onAllianceChanged(AllianceMemberChangedEvent event) {
        messages.tellLocal(self, ignored -> apply(event));
    }

    public Optional<ScenePlayerAllianceView> allianceOf(long playerId) {
        return Optional.ofNullable(alliances.get(playerId));
    }

    public long revisionOf(long allianceId) {
        return checkpoint.revisionOf("alliance:" + allianceId);
    }

    private void apply(AllianceMemberChangedEvent event) {
        if (!onlinePlayers.contains(event.playerId())) {
            return;
        }
        SubscriptionDecision decision = checkpoint.inspect(event);
        if (decision == SubscriptionDecision.DUPLICATE_OR_OLD) {
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
