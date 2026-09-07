package com.commonbattle.game.player.event;

import com.commonbattle.game.event.SubscriptionCheckpoint;
import com.commonbattle.game.event.SubscriptionDecision;
import com.commonbattle.game.event.VersionedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家领域事件消费器。
 * 它为订阅方统一处理 ownerKey + revision 幂等、缺口标脏和按 eventType 分发。
 */
public final class PlayerDomainEventProcessor {
    private final SubscriptionCheckpoint checkpoint = new SubscriptionCheckpoint();
    private final Map<String, CopyOnWriteArrayList<PlayerDomainEventHandler>> handlers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PlayerDomainEventHandler> anyHandlers = new CopyOnWriteArrayList<>();
    private final Set<String> staleOwners = ConcurrentHashMap.newKeySet();
    private final AtomicLong appliedEvents = new AtomicLong();
    private final AtomicLong duplicateOrOldEvents = new AtomicLong();
    private final AtomicLong gapEvents = new AtomicLong();
    private final AtomicLong ignoredEvents = new AtomicLong();

    public AutoCloseable on(String eventType, PlayerDomainEventHandler handler) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(handler, "handler");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        CopyOnWriteArrayList<PlayerDomainEventHandler> eventHandlers =
                handlers.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>());
        eventHandlers.add(handler);
        return () -> eventHandlers.remove(handler);
    }

    public AutoCloseable onAny(PlayerDomainEventHandler handler) {
        anyHandlers.add(Objects.requireNonNull(handler, "handler"));
        return () -> anyHandlers.remove(handler);
    }

    public SubscriptionDecision apply(VersionedEvent event) {
        if (!(event instanceof PlayerDomainVersionedEvent playerEvent)) {
            throw new IllegalArgumentException("event must be PlayerDomainVersionedEvent");
        }
        SubscriptionDecision decision = checkpoint.inspect(playerEvent);
        if (decision == SubscriptionDecision.DUPLICATE_OR_OLD) {
            duplicateOrOldEvents.incrementAndGet();
            return decision;
        }
        boolean stale = decision == SubscriptionDecision.GAP;
        if (stale) {
            staleOwners.add(playerEvent.ownerKey());
            gapEvents.incrementAndGet();
        } else {
            staleOwners.remove(playerEvent.ownerKey());
            appliedEvents.incrementAndGet();
        }
        PlayerDomainEventDelivery delivery = new PlayerDomainEventDelivery(playerEvent, decision, stale);
        List<PlayerDomainEventHandler> targets = handlers(playerEvent.eventType());
        if (targets.isEmpty()) {
            ignoredEvents.incrementAndGet();
        } else {
            targets.forEach(handler -> handler.handle(delivery));
        }
        checkpoint.markApplied(playerEvent);
        return decision;
    }

    public void repairOwner(String ownerKey, long revision) {
        checkpoint.reset(ownerKey, revision);
        staleOwners.remove(ownerKey);
    }

    public boolean stale(long playerId) {
        return staleOwners.contains(PlayerDomainVersionedEvent.ownerKey(playerId));
    }

    public boolean stale(String ownerKey) {
        return staleOwners.contains(ownerKey);
    }

    public long revisionOf(long playerId) {
        return revisionOf(PlayerDomainVersionedEvent.ownerKey(playerId));
    }

    public long revisionOf(String ownerKey) {
        return checkpoint.revisionOf(ownerKey);
    }

    public Map<String, Long> knownRevisions() {
        return checkpoint.snapshot();
    }

    public PlayerDomainEventProcessorStats stats() {
        return new PlayerDomainEventProcessorStats(
                appliedEvents.get(),
                duplicateOrOldEvents.get(),
                gapEvents.get(),
                ignoredEvents.get(),
                staleOwners.size()
        );
    }

    private List<PlayerDomainEventHandler> handlers(String eventType) {
        List<PlayerDomainEventHandler> result = new ArrayList<>();
        result.addAll(handlers.getOrDefault(eventType, new CopyOnWriteArrayList<>()));
        result.addAll(anyHandlers);
        return result;
    }
}
