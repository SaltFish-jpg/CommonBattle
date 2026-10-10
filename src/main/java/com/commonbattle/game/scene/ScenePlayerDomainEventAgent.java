package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.event.SubscriptionDecision;
import com.commonbattle.game.player.event.BagChangedEvent;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.PlayerDomainEventDelivery;
import com.commonbattle.game.player.event.PlayerDomainEventProcessor;
import com.commonbattle.game.player.event.PlayerDomainProjectionSnapshot;
import com.commonbattle.game.player.event.PlayerDomainProjectionSnapshotReader;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.snapshot.SnapshotReadMetrics;
import com.commonbattle.game.snapshot.SnapshotReadStats;
import com.commonbattle.game.snapshot.SnapshotReadStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 场景侧玩家领域事件感知 Agent。
 * 示例只统计在线玩家通关次数，真实 Scene 可在这里刷新场景内称号、战斗表现、任务提示等派生视图。
 */
public final class ScenePlayerDomainEventAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private final PlayerDomainEventProcessor processor;
    private final PlayerDomainProjectionSnapshotReader snapshotReader;
    private OwnerEventInterestControl interests;
    private final Set<Long> onlinePlayers = new HashSet<>();
    private final Map<Long, Integer> stageClears = new HashMap<>();
    private final Map<Long, Map<String, Integer>> bagItems = new HashMap<>();
    private long repairRequests;
    private long appliedSnapshots;
    private long ignoredSnapshots;
    private final SnapshotReadMetrics freshReads = new SnapshotReadMetrics();

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
        this(messages, self, processor, interests, ignored -> Optional.empty());
    }

    public ScenePlayerDomainEventAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerDomainEventProcessor processor,
            OwnerEventInterestControl interests,
            PlayerDomainProjectionSnapshotReader snapshotReader
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.self = Objects.requireNonNull(self, "self");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.interests = Objects.requireNonNull(interests, "interests");
        this.snapshotReader = Objects.requireNonNull(snapshotReader, "snapshotReader");
        this.processor.on(BattleStageClearedEvent.TYPE, this::onBattleStageCleared);
        this.processor.on(BagChangedEvent.TYPE, this::onBagChanged);
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
                bagItems.remove(playerId);
                interests.unwatchOwner(PlayerDomainVersionedEvent.ownerKey(playerId));
            }
        });
    }

    public void onPlayerDomainEvent(PlayerDomainVersionedEvent event) {
        messages.tellLocal(self, ignored -> handlePlayerDomainEvent(event));
    }

    public void onPlayerDomainEventAndReadFreshResult(
            PlayerDomainVersionedEvent event,
            Consumer<ScenePlayerDomainReadResult> callback
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(callback, "callback");
        messages.tellLocal(self, ignored -> {
            handlePlayerDomainEvent(event);
            callback.accept(readFreshView(event.playerId(), event.revision()));
        });
    }

    public void handlePlayerDomainEvent(PlayerDomainVersionedEvent event) {
        ActorContext.requireCurrent(self);
        Objects.requireNonNull(event, "event");
        if (onlinePlayers.contains(event.playerId())) {
            SubscriptionDecision decision = processor.apply(event);
            if (decision == SubscriptionDecision.GAP) {
                repairRequests++;
                interests.requestRepairOwner(event.ownerKey());
            }
        }
    }

    public void refresh(PlayerDomainProjectionSnapshot snapshot) {
        messages.tellLocal(self, ignored -> handleSnapshot(snapshot));
    }

    public void handleSnapshot(PlayerDomainProjectionSnapshot snapshot) {
        ActorContext.requireCurrent(self);
        Objects.requireNonNull(snapshot, "snapshot");
        if (!onlinePlayers.contains(snapshot.playerId())) {
            ignoredSnapshots++;
            return;
        }
        if (snapshot.eventRevision() < processor.revisionOf(snapshot.playerId())) {
            ignoredSnapshots++;
            return;
        }
        appliedSnapshots++;
        int clears = snapshot.totalStageClears();
        if (clears > 0) {
            stageClears.put(snapshot.playerId(), clears);
        } else {
            stageClears.remove(snapshot.playerId());
        }
        if (snapshot.bagItemCounts().isEmpty()) {
            bagItems.remove(snapshot.playerId());
        } else {
            bagItems.put(snapshot.playerId(), new HashMap<>(snapshot.bagItemCounts()));
        }
        processor.repairOwner(snapshot.ownerKey(), snapshot.eventRevision());
    }

    public ScenePlayerDomainReadResult readFreshView(long playerId, long minimumRevision) {
        ActorContext.requireCurrent(self);
        if (minimumRevision < 0) {
            throw new IllegalArgumentException("minimumRevision must not be negative");
        }
        freshReads.request();
        Optional<ScenePlayerDomainView> local = viewOf(playerId).filter(view -> view.freshAtLeast(minimumRevision));
        if (local.isPresent()) {
            freshReads.record(SnapshotReadStatus.LOCAL_HIT);
            return ScenePlayerDomainReadResult.of(local.orElseThrow(), ScenePlayerDomainReadStatus.LOCAL_HIT);
        }
        Optional<PlayerDomainProjectionSnapshot> snapshot = snapshotReader.find(playerId);
        if (snapshot.isPresent() && snapshot.orElseThrow().eventRevision() >= minimumRevision) {
            handleSnapshot(snapshot.orElseThrow());
            Optional<ScenePlayerDomainView> refreshed = viewOf(playerId)
                    .filter(view -> view.freshAtLeast(minimumRevision));
            if (refreshed.isPresent()) {
                freshReads.record(SnapshotReadStatus.REFRESHED);
                return ScenePlayerDomainReadResult.of(refreshed.orElseThrow(), ScenePlayerDomainReadStatus.REFRESHED);
            }
            freshReads.record(SnapshotReadStatus.REMOTE_STALE);
            return ScenePlayerDomainReadResult.empty(ScenePlayerDomainReadStatus.REMOTE_STALE);
        }
        if (snapshot.isPresent()) {
            freshReads.record(SnapshotReadStatus.REMOTE_STALE);
            return ScenePlayerDomainReadResult.empty(ScenePlayerDomainReadStatus.REMOTE_STALE);
        }
        freshReads.record(SnapshotReadStatus.REMOTE_MISS);
        return ScenePlayerDomainReadResult.empty(ScenePlayerDomainReadStatus.REMOTE_MISS);
    }

    public Optional<ScenePlayerDomainView> viewOf(long playerId) {
        long revision = revisionOf(playerId);
        if (revision == 0 && !stageClears.containsKey(playerId) && !bagItems.containsKey(playerId)) {
            return Optional.empty();
        }
        return Optional.of(new ScenePlayerDomainView(
                playerId,
                revision,
                stale(playerId),
                stageClears.getOrDefault(playerId, 0),
                bagItems.getOrDefault(playerId, Map.of())
        ));
    }

    public OptionalInt stageClears(long playerId) {
        Integer clears = stageClears.get(playerId);
        return clears == null ? OptionalInt.empty() : OptionalInt.of(clears);
    }

    public OptionalInt bagCount(long playerId, String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        Map<String, Integer> counts = bagItems.get(playerId);
        if (counts == null || !counts.containsKey(itemId)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(counts.get(itemId));
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

    public SceneProjectionStats projectionStats() {
        var stats = processor.stats();
        SnapshotReadStats readStats = freshReads.snapshot();
        return new SceneProjectionStats(
                stats.appliedEvents() + stats.duplicateOrOldEvents() + stats.gapEvents() + stats.ignoredEvents(),
                stats.appliedEvents() + stats.gapEvents(),
                stats.duplicateOrOldEvents(),
                stats.gapEvents(),
                repairRequests,
                appliedSnapshots,
                ignoredSnapshots,
                stats.staleOwners(),
                readStats.requests(),
                readStats.localHits(),
                readStats.refreshes(),
                readStats.remoteStale(),
                readStats.remoteMisses()
        );
    }

    private void onBattleStageCleared(PlayerDomainEventDelivery delivery) {
        PlayerDomainVersionedEvent event = delivery.event();
        // 场景派生视图边界：事件 revision 通过 processor 去重后，才更新场景本地只读视图。
        stageClears.merge(event.playerId(), event.delta(), Integer::sum);
    }

    private void onBagChanged(PlayerDomainEventDelivery delivery) {
        PlayerDomainVersionedEvent event = delivery.event();
        Map<String, Integer> counts = bagItems.computeIfAbsent(event.playerId(), ignored -> new HashMap<>());
        int changeCount = parseInt(event.attributes().get("changeCount"), 0);
        for (int index = 0; index < changeCount; index++) {
            String itemId = event.attributes().get("change." + index + ".itemId");
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            int after = parseInt(event.attributes().get("change." + index + ".after"), 0);
            if (after <= 0) {
                counts.remove(itemId);
            } else {
                counts.put(itemId, after);
            }
        }
        if (counts.isEmpty()) {
            bagItems.remove(event.playerId());
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
