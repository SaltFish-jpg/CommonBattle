package com.commonbattle.game.profile;

import com.commonbattle.cluster.event.ClusterEventSubscriptionManager;
import com.commonbattle.cluster.event.SubscriptionCursor;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.snapshot.EventReplaySnapshotRepairer;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 玩家资料本地缓存的跨服订阅装配。
 * Scene、Chat 等非 owner 服务用它订阅 ProfileChangedEvent，并在事件中心历史缺口时回源修补快照。
 */
public final class ProfileCacheSubscription implements AutoCloseable {
    private final LocalProfileCache cache;
    private final AutoCloseable registration;

    private ProfileCacheSubscription(LocalProfileCache cache, AutoCloseable registration) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.registration = Objects.requireNonNull(registration, "registration");
    }

    public static ProfileCacheSubscription register(
            ClusterEventSubscriptionManager manager,
            LocalProfileCache cache,
            ProfileSnapshotReader repairReader
    ) {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(repairReader, "repairReader");
        EventReplaySnapshotRepairer repairer = new EventReplaySnapshotRepairer()
                .register(ProfileChangedEvent.TOPIC, new ProfileSnapshotRepairer(repairReader, cache));
        AutoCloseable registration = manager.register(
                ProfileChangedEvent.TOPIC,
                event -> apply(cache, event),
                cache::knownRevisions,
                repairer
        );
        return new ProfileCacheSubscription(cache, registration);
    }

    public static ProfileCacheSubscription register(
            ClusterEventSubscriptionManager manager,
            LocalProfileCache cache,
            ProfileSnapshotReader repairReader,
            Set<Long> playerIds
    ) {
        Objects.requireNonNull(playerIds, "playerIds");
        if (playerIds.isEmpty()) {
            throw new IllegalArgumentException("playerIds must not be empty");
        }
        Set<String> ownerKeys = playerIds.stream()
                .map(ProfileChangedEvent::ownerKey)
                .collect(Collectors.toUnmodifiableSet());
        EventReplaySnapshotRepairer repairer = new EventReplaySnapshotRepairer()
                .register(ProfileChangedEvent.TOPIC, new ProfileSnapshotRepairer(repairReader, cache));
        AutoCloseable registration = manager.register(
                ProfileChangedEvent.TOPIC,
                event -> applyIfInterested(cache, ownerKeys, event),
                cursor(cache, ownerKeys),
                repairer
        );
        return new ProfileCacheSubscription(cache, registration);
    }

    public LocalProfileCache cache() {
        return cache;
    }

    @Override
    public void close() throws Exception {
        registration.close();
    }

    private static void apply(LocalProfileCache cache, VersionedEvent event) {
        if (!(event instanceof ProfileChangedEvent profileChanged)) {
            throw new IllegalArgumentException("event must be ProfileChangedEvent");
        }
        cache.apply(profileChanged);
    }

    private static void applyIfInterested(LocalProfileCache cache, Set<String> ownerKeys, VersionedEvent event) {
        if (ownerKeys.contains(event.ownerKey())) {
            apply(cache, event);
        }
    }

    private static SubscriptionCursor cursor(LocalProfileCache cache, Set<String> ownerKeys) {
        return new SubscriptionCursor() {
            @Override
            public java.util.Map<String, Long> knownRevisions() {
                return ownerKeys.stream()
                        .collect(Collectors.toUnmodifiableMap(ownerKey -> ownerKey, ownerKey -> cache.revisionOf(playerId(ownerKey))));
            }

            @Override
            public Set<String> ownerKeys() {
                return ownerKeys;
            }
        };
    }

    private static long playerId(String ownerKey) {
        return ProfileOwnerKeyParser.INSTANCE.parse(ownerKey)
                .orElseThrow(() -> new IllegalArgumentException("invalid profile ownerKey"));
    }
}
