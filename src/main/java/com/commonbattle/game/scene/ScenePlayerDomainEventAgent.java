package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.event.SubscriptionDecision;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.PlayerDomainEventDelivery;
import com.commonbattle.game.player.event.PlayerDomainEventProcessor;
import com.commonbattle.game.player.event.PlayerDomainProjectionSnapshot;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 场景侧玩家领域事件感知 Agent。
 * 示例只统计在线玩家通关次数，真实 Scene 可在这里刷新场景内称号、战斗表现、任务提示等派生视图。
 */
public final class ScenePlayerDomainEventAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private final PlayerDomainEventProcessor processor;
    private OwnerEventInterestControl interests;
    private final Set<Long> onlinePlayers = new HashSet<>();
    private final Map<Long, Integer> stageClears = new HashMap<>();

    public ScenePlayerDomainEventAgent(AgentMessagePort messages, ActorRef self) {
        this(messages, self, new PlayerDomainEventProcessor(), OwnerEventInterestControl.noop());
    }

    public ScenePlayerDomainEventAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerDomainEventProcessor processor
    ) {
        this(messages, self, processor, OwnerEventInterestControl.noop());
    }

    public ScenePlayerDomainEventAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerDomainEventProcessor processor,
            OwnerEventInterestControl interests
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.self = Objects.requireNonNull(self, "self");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.interests = Objects.requireNonNull(interests, "interests");
        this.processor.on(BattleStageClearedEvent.TYPE, this::onBattleStageCleared);
    }

    public void attachInterests(OwnerEventInterestControl interests) {
        this.interests = Objects.requireNonNull(interests, "interests");
    }

    public void enter(long playerId) {
        messages.tellLocal(self, ignored -> {
            if (onlinePlayers.add(playerId)) {
                interests.watchOwner(PlayerDomainVersionedEvent.ownerKey(playerId));
            }
        });
    }

    public void leave(long playerId) {
        messages.tellLocal(self, ignored -> {
            if (onlinePlayers.remove(playerId)) {
                stageClears.remove(playerId);
                interests.unwatchOwner(PlayerDomainVersionedEvent.ownerKey(playerId));
            }
        });
    }

    public void onPlayerDomainEvent(PlayerDomainVersionedEvent event) {
        messages.tellLocal(self, ignored -> handlePlayerDomainEvent(event));
    }

    public void handlePlayerDomainEvent(PlayerDomainVersionedEvent event) {
        Objects.requireNonNull(event, "event");
        if (onlinePlayers.contains(event.playerId())) {
            SubscriptionDecision decision = processor.apply(event);
            if (decision == SubscriptionDecision.GAP) {
                interests.requestRepairOwner(event.ownerKey());
            }
        }
    }

    public void refresh(PlayerDomainProjectionSnapshot snapshot) {
        messages.tellLocal(self, ignored -> handleSnapshot(snapshot));
    }

    public void handleSnapshot(PlayerDomainProjectionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!onlinePlayers.contains(snapshot.playerId())) {
            return;
        }
        if (snapshot.eventRevision() < processor.revisionOf(snapshot.playerId())) {
            return;
        }
        int clears = snapshot.totalStageClears();
        if (clears > 0) {
            stageClears.put(snapshot.playerId(), clears);
        } else {
            stageClears.remove(snapshot.playerId());
        }
        processor.repairOwner(snapshot.ownerKey(), snapshot.eventRevision());
    }

    public OptionalInt stageClears(long playerId) {
        Integer clears = stageClears.get(playerId);
        return clears == null ? OptionalInt.empty() : OptionalInt.of(clears);
    }

    public boolean stale(long playerId) {
        return processor.stale(playerId);
    }

    public long revisionOf(long playerId) {
        return processor.revisionOf(playerId);
    }

    public PlayerDomainEventProcessor processor() {
        return processor;
    }

    private void onBattleStageCleared(PlayerDomainEventDelivery delivery) {
        PlayerDomainVersionedEvent event = delivery.event();
        // 场景派生视图边界：事件 revision 通过 processor 去重后，才更新场景本地只读视图。
        stageClears.merge(event.playerId(), event.delta(), Integer::sum);
    }
}
