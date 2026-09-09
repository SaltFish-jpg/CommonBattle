package com.commonbattle.game.profile;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.cluster.event.ClusterVersionedEventBus;
import com.commonbattle.cluster.event.EventReplayResult;
import com.commonbattle.game.event.SubscriptionDecision;
import com.commonbattle.game.event.VersionedEvent;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Profile owner 动态关注订阅。
 * 场景、聊天等服务在玩家进入本进程业务范围时 watch，离开时 unwatch，避免 topic 级广播全服资料事件。
 */
public final class ProfileInterestSubscription implements ProfileInterestControl, ProfileInterestView, AutoCloseable {
    private final ClusterVersionedEventBus bus;
    private final LocalProfileCache cache;
    private final ProfileSnapshotRepairer repairer;
    private final Executor repairExecutor;
    private final ConcurrentHashMap<String, Integer> ownerReferences = new ConcurrentHashMap<>();
    private final Set<String> replayingOwners = ConcurrentHashMap.newKeySet();
    private final AutoCloseable localSubscription;
    private final AtomicLong watchRequests = new AtomicLong();
    private final AtomicLong unwatchRequests = new AtomicLong();
    private final AtomicLong replayAttempts = new AtomicLong();
    private final AtomicLong replayFailures = new AtomicLong();
    private final AtomicLong repairRequests = new AtomicLong();
    private final AtomicLong repairFailures = new AtomicLong();

    public ProfileInterestSubscription(
            ClusterVersionedEventBus bus,
            LocalProfileCache cache,
            ProfileSnapshotReader repairReader
    ) {
        this(bus, cache, repairReader, Runnable::run);
    }

    public ProfileInterestSubscription(
            ClusterVersionedEventBus bus,
            LocalProfileCache cache,
            ProfileSnapshotReader repairReader,
            Executor repairExecutor
    ) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.repairer = new ProfileSnapshotRepairer(Objects.requireNonNull(repairReader, "repairReader"), cache);
        this.repairExecutor = Objects.requireNonNull(repairExecutor, "repairExecutor");
        this.localSubscription = bus.listenLocal(ProfileChangedEvent.TOPIC, this::onEvent);
    }

    public void watch(long playerId) {
        watchAll(Set.of(playerId));
    }

    public void unwatch(long playerId) {
        unwatchAll(Set.of(playerId));
    }

    @Override
    public void watchAll(Collection<Long> playerIds) {
        Objects.requireNonNull(playerIds, "playerIds");
        if (playerIds.isEmpty()) {
            return;
        }
        watchRequests.addAndGet(playerIds.size());
        Map<String, Long> firstOwners = new HashMap<>();
        for (long playerId : playerIds) {
            String ownerKey = ProfileChangedEvent.ownerKey(playerId);
            if (addReference(ownerKey)) {
                firstOwners.put(ownerKey, cache.revisionOf(playerId));
            }
        }
        if (firstOwners.isEmpty()) {
            return;
        }
        Set<String> ownerKeys = Set.copyOf(firstOwners.keySet());
        bus.subscribeRemote(ProfileChangedEvent.TOPIC, ownerKeys);
        replay(firstOwners, ownerKeys);
    }

    @Override
    public void unwatchAll(Collection<Long> playerIds) {
        Objects.requireNonNull(playerIds, "playerIds");
        if (playerIds.isEmpty()) {
            return;
        }
        Set<String> removedOwners = new HashSet<>();
        long removedReferences = 0;
        for (long playerId : playerIds) {
            String ownerKey = ProfileChangedEvent.ownerKey(playerId);
            UnwatchResult result = removeReference(ownerKey);
            if (result.counted()) {
                removedReferences++;
            }
            if (result.unsubscribe()) {
                removedOwners.add(ownerKey);
            }
        }
        unwatchRequests.addAndGet(removedReferences);
        if (!removedOwners.isEmpty()) {
            bus.unsubscribeRemote(ProfileChangedEvent.TOPIC, removedOwners);
        }
    }

    public boolean watching(long playerId) {
        return ownerReferences.containsKey(ProfileChangedEvent.ownerKey(playerId));
    }

    @Override
    public void requestRepair(long playerId) {
        requestRepairAll(Set.of(playerId));
    }

    @Override
    public void requestRepairAll(Collection<Long> playerIds) {
        Objects.requireNonNull(playerIds, "playerIds");
        if (playerIds.isEmpty()) {
            return;
        }
        Set<String> ownerKeys = new HashSet<>();
        for (long playerId : playerIds) {
            String ownerKey = ProfileChangedEvent.ownerKey(playerId);
            if (ownerReferences.containsKey(ownerKey)) {
                ownerKeys.add(ownerKey);
            }
        }
        if (!ownerKeys.isEmpty()) {
            repairOwners(ownerKeys);
        }
    }

    public ProfileInterestStats stats() {
        return new ProfileInterestStats(
                ownerReferences.size(),
                ownerReferences.values().stream().mapToInt(Integer::intValue).sum(),
                watchRequests.get(),
                unwatchRequests.get(),
                replayAttempts.get(),
                replayFailures.get(),
                repairRequests.get(),
                repairFailures.get()
        );
    }

    @Override
    public void close() throws Exception {
        Set<String> closingOwners = Set.copyOf(ownerReferences.keySet());
        ownerReferences.clear();
        try {
            localSubscription.close();
        } finally {
            if (!closingOwners.isEmpty()) {
                bus.unsubscribeRemote(ProfileChangedEvent.TOPIC, closingOwners);
            }
        }
    }

    private void replay(Map<String, Long> knownRevisions, Set<String> ownerKeys) {
        replayAttempts.incrementAndGet();
        replayingOwners.addAll(ownerKeys);
        bus.replay(
                ProfileChangedEvent.TOPIC,
                knownRevisions,
                ownerKeys,
                new RpcCallback<>() {
                    @Override
                    public void success(EventReplayResult response) {
                        try {
                            repair(response);
                        } finally {
                            replayingOwners.removeAll(ownerKeys);
                        }
                    }

                    @Override
                    public void failure(Throwable error) {
                        try {
                            replayFailures.incrementAndGet();
                        } finally {
                            replayingOwners.removeAll(ownerKeys);
                        }
                    }
                }
        );
    }

    private boolean addReference(String ownerKey) {
        AtomicBoolean first = new AtomicBoolean();
        ownerReferences.compute(ownerKey, (ignored, current) -> {
            if (current == null) {
                first.set(true);
                return 1;
            }
            return current + 1;
        });
        return first.get();
    }

    private UnwatchResult removeReference(String ownerKey) {
        AtomicBoolean counted = new AtomicBoolean();
        AtomicBoolean unsubscribe = new AtomicBoolean();
        ownerReferences.computeIfPresent(ownerKey, (ignored, current) -> {
            counted.set(true);
            if (current <= 1) {
                unsubscribe.set(true);
                return null;
            }
            return current - 1;
        });
        return new UnwatchResult(counted.get(), unsubscribe.get());
    }

    private void repair(EventReplayResult response) {
        if (response.unavailableOwnerKeys().isEmpty()) {
            return;
        }
        repairOwners(response.unavailableOwnerKeys());
    }

    private void repairOwners(Set<String> ownerKeys) {
        repairRequests.incrementAndGet();
        try {
            repairExecutor.execute(() -> repairNow(ownerKeys));
        } catch (RuntimeException e) {
            repairFailures.incrementAndGet();
        }
    }

    private void repairNow(Set<String> ownerKeys) {
        try {
            repairer.repair(ownerKeys);
        } catch (RuntimeException e) {
            repairFailures.incrementAndGet();
        }
    }

    private void onEvent(VersionedEvent event) {
        if (!ownerReferences.containsKey(event.ownerKey())) {
            return;
        }
        if (!(event instanceof ProfileChangedEvent profileChanged)) {
            throw new IllegalArgumentException("event must be ProfileChangedEvent");
        }
        SubscriptionDecision decision = cache.apply(profileChanged);
        if (decision == SubscriptionDecision.GAP && !replayingOwners.contains(event.ownerKey())) {
            repairOwners(Set.of(event.ownerKey()));
        }
    }

    private record UnwatchResult(boolean counted, boolean unsubscribe) {
    }
}
