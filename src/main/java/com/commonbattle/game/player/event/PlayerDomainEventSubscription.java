package com.commonbattle.game.player.event;

import com.commonbattle.cluster.event.ClusterEventSubscriptionManager;
import com.commonbattle.cluster.event.EventReplayRepairer;
import com.commonbattle.cluster.event.SubscriptionCursor;
import com.commonbattle.game.event.VersionedEvent;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 玩家领域事件订阅装配。
 * 订阅方用它把 PlayerDomainEventProcessor 挂到跨服事件中心，并可按玩家 ownerKey 做过滤订阅。
 */
public final class PlayerDomainEventSubscription implements AutoCloseable {
    private final PlayerDomainEventProcessor processor;
    private final AutoCloseable registration;

    private PlayerDomainEventSubscription(PlayerDomainEventProcessor processor, AutoCloseable registration) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.registration = Objects.requireNonNull(registration, "registration");
    }

    public static PlayerDomainEventSubscription register(
            ClusterEventSubscriptionManager manager,
            PlayerDomainEventProcessor processor
    ) {
        return register(manager, processor, Set.of(), EventReplayRepairer.noop());
    }

    public static PlayerDomainEventSubscription register(
            ClusterEventSubscriptionManager manager,
            PlayerDomainEventProcessor processor,
            Set<Long> playerIds,
            EventReplayRepairer repairer
    ) {
        Objects.requireNonNull(playerIds, "playerIds");
        Set<String> ownerKeys = playerIds.stream()
                .map(PlayerDomainVersionedEvent::ownerKey)
                .collect(Collectors.toUnmodifiableSet());
        AutoCloseable registration = Objects.requireNonNull(manager, "manager").register(
                PlayerDomainVersionedEvent.TOPIC,
                event -> apply(processor, ownerKeys, event),
                cursor(processor, ownerKeys),
                Objects.requireNonNull(repairer, "repairer")
        );
        return new PlayerDomainEventSubscription(processor, registration);
    }

    public PlayerDomainEventProcessor processor() {
        return processor;
    }

    @Override
    public void close() throws Exception {
        registration.close();
    }

    private static void apply(PlayerDomainEventProcessor processor, Set<String> ownerKeys, VersionedEvent event) {
        if (!ownerKeys.isEmpty() && !ownerKeys.contains(event.ownerKey())) {
            return;
        }
        processor.apply(event);
    }

    private static SubscriptionCursor cursor(PlayerDomainEventProcessor processor, Set<String> ownerKeys) {
        if (ownerKeys.isEmpty()) {
            return processor::knownRevisions;
        }
        return new SubscriptionCursor() {
            @Override
            public Map<String, Long> knownRevisions() {
                return ownerKeys.stream()
                        .collect(Collectors.toUnmodifiableMap(ownerKey -> ownerKey, processor::revisionOf));
            }

            @Override
            public Set<String> ownerKeys() {
                return ownerKeys;
            }
        };
    }
}
