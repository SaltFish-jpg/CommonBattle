package com.commonbattle.game.profile;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.cluster.event.ClusterVersionedEventBus;
import com.commonbattle.cluster.event.EventReplayResult;
import com.commonbattle.game.event.VersionedEvent;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
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
    private final Set<String> ownerKeys = ConcurrentHashMap.newKeySet();
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
        String ownerKey = ProfileChangedEvent.ownerKey(playerId);
        if (!ownerKeys.add(ownerKey)) {
            return;
        }
        watchRequests.incrementAndGet();
        bus.subscribeRemote(ProfileChangedEvent.TOPIC, Set.of(ownerKey));
        replay(playerId, ownerKey);
    }

    public void unwatch(long playerId) {
        String ownerKey = ProfileChangedEvent.ownerKey(playerId);
        if (!ownerKeys.remove(ownerKey)) {
            return;
        }
        unwatchRequests.incrementAndGet();
        bus.unsubscribeRemote(ProfileChangedEvent.TOPIC, Set.of(ownerKey));
    }

    public boolean watching(long playerId) {
        return ownerKeys.contains(ProfileChangedEvent.ownerKey(playerId));
    }

    public ProfileInterestStats stats() {
        return new ProfileInterestStats(
                ownerKeys.size(),
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
        Set<String> closingOwners = Set.copyOf(ownerKeys);
        ownerKeys.clear();
        try {
            localSubscription.close();
        } finally {
            if (!closingOwners.isEmpty()) {
                bus.unsubscribeRemote(ProfileChangedEvent.TOPIC, closingOwners);
            }
        }
    }

    private void replay(long playerId, String ownerKey) {
        replayAttempts.incrementAndGet();
        bus.replay(
                ProfileChangedEvent.TOPIC,
                Map.of(ownerKey, cache.revisionOf(playerId)),
                Set.of(ownerKey),
                new RpcCallback<>() {
                    @Override
                    public void success(EventReplayResult response) {
                        repair(response);
                    }

                    @Override
                    public void failure(Throwable error) {
                        replayFailures.incrementAndGet();
                    }
                }
        );
    }

    private void repair(EventReplayResult response) {
        if (response.unavailableOwnerKeys().isEmpty()) {
            return;
        }
        repairRequests.incrementAndGet();
        try {
            repairExecutor.execute(() -> repairNow(response.unavailableOwnerKeys()));
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
        if (!ownerKeys.contains(event.ownerKey())) {
            return;
        }
        if (!(event instanceof ProfileChangedEvent profileChanged)) {
            throw new IllegalArgumentException("event must be ProfileChangedEvent");
        }
        cache.apply(profileChanged);
    }
}
