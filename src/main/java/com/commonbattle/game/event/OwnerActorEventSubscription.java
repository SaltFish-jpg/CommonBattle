package com.commonbattle.game.event;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.cluster.event.ClusterVersionedEventBus;
import com.commonbattle.cluster.event.EventReplayRepairer;
import com.commonbattle.cluster.event.EventReplayResult;

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
import java.util.function.ToLongFunction;

/**
 * owner 关注型跨服事件订阅模板。
 * 它统一处理引用计数、远端订阅、replay 补偿和本地事件过滤，最终把事件交给 ActorMailboxEventSubscriber。
 */
public final class OwnerActorEventSubscription
        implements OwnerEventInterestControl, OwnerActorEventSubscriptionView, AutoCloseable {
    private final ClusterVersionedEventBus bus;
    private final String topic;
    private final VersionedEventSubscriber subscriber;
    private final ToLongFunction<String> revisionReader;
    private final EventReplayRepairer repairer;
    private final Executor repairExecutor;
    private final ConcurrentHashMap<String, Integer> ownerReferences = new ConcurrentHashMap<>();
    private final AutoCloseable localSubscription;
    private final AtomicLong watchRequests = new AtomicLong();
    private final AtomicLong unwatchRequests = new AtomicLong();
    private final AtomicLong subscribeRequests = new AtomicLong();
    private final AtomicLong unsubscribeRequests = new AtomicLong();
    private final AtomicLong replayAttempts = new AtomicLong();
    private final AtomicLong replayFailures = new AtomicLong();
    private final AtomicLong repairRequests = new AtomicLong();
    private final AtomicLong repairOwnerCount = new AtomicLong();
    private final AtomicLong repairFailures = new AtomicLong();

    public OwnerActorEventSubscription(
            ClusterVersionedEventBus bus,
            String topic,
            VersionedEventSubscriber subscriber,
            ToLongFunction<String> revisionReader,
            EventReplayRepairer repairer
    ) {
        this(bus, topic, subscriber, revisionReader, repairer, Runnable::run);
    }

    public OwnerActorEventSubscription(
            ClusterVersionedEventBus bus,
            String topic,
            VersionedEventSubscriber subscriber,
            ToLongFunction<String> revisionReader,
            EventReplayRepairer repairer,
            Executor repairExecutor
    ) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.subscriber = Objects.requireNonNull(subscriber, "subscriber");
        this.revisionReader = Objects.requireNonNull(revisionReader, "revisionReader");
        this.repairer = Objects.requireNonNull(repairer, "repairer");
        this.repairExecutor = Objects.requireNonNull(repairExecutor, "repairExecutor");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        this.localSubscription = bus.listenLocal(topic, this::onEvent);
    }

    @Override
    public void watchOwner(String ownerKey) {
        watchOwners(Set.of(ownerKey));
    }

    @Override
    public void watchOwners(Collection<String> ownerKeys) {
        Objects.requireNonNull(ownerKeys, "ownerKeys");
        if (ownerKeys.isEmpty()) {
            return;
        }
        watchRequests.addAndGet(ownerKeys.size());
        Map<String, Long> firstOwners = new HashMap<>();
        for (String ownerKey : ownerKeys) {
            validateOwnerKey(ownerKey);
            if (addReference(ownerKey)) {
                firstOwners.put(ownerKey, revisionReader.applyAsLong(ownerKey));
            }
        }
        if (firstOwners.isEmpty()) {
            return;
        }
        Set<String> newOwners = Set.copyOf(firstOwners.keySet());
        subscribeRequests.incrementAndGet();
        bus.subscribeRemote(topic, newOwners);
        replay(firstOwners, newOwners);
    }

    @Override
    public void unwatchOwner(String ownerKey) {
        unwatchOwners(Set.of(ownerKey));
    }

    @Override
    public void unwatchOwners(Collection<String> ownerKeys) {
        Objects.requireNonNull(ownerKeys, "ownerKeys");
        if (ownerKeys.isEmpty()) {
            return;
        }
        Set<String> removedOwners = new HashSet<>();
        long removedReferences = 0;
        for (String ownerKey : ownerKeys) {
            validateOwnerKey(ownerKey);
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
            unsubscribeRequests.incrementAndGet();
            bus.unsubscribeRemote(topic, removedOwners);
        }
    }

    public void recover() {
        Map<String, Long> watched = watchedRevisions();
        if (watched.isEmpty()) {
            return;
        }
        subscribeRequests.incrementAndGet();
        bus.renewRemote(topic, watched.keySet());
        replay(watched, watched.keySet());
    }

    @Override
    public void requestRepairOwners(Collection<String> ownerKeys) {
        Objects.requireNonNull(ownerKeys, "ownerKeys");
        if (ownerKeys.isEmpty()) {
            return;
        }
        Set<String> watched = new HashSet<>();
        for (String ownerKey : ownerKeys) {
            validateOwnerKey(ownerKey);
            if (ownerReferences.containsKey(ownerKey)) {
                watched.add(ownerKey);
            }
        }
        if (!watched.isEmpty()) {
            repairOwners(watched);
        }
    }

    public boolean watching(String ownerKey) {
        validateOwnerKey(ownerKey);
        return ownerReferences.containsKey(ownerKey);
    }

    @Override
    public OwnerActorEventSubscriptionStats stats() {
        return new OwnerActorEventSubscriptionStats(
                ownerReferences.size(),
                ownerReferences.values().stream().mapToInt(Integer::intValue).sum(),
                watchRequests.get(),
                unwatchRequests.get(),
                subscribeRequests.get(),
                unsubscribeRequests.get(),
                replayAttempts.get(),
                replayFailures.get(),
                repairRequests.get(),
                repairOwnerCount.get(),
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
                unsubscribeRequests.incrementAndGet();
                bus.unsubscribeRemote(topic, closingOwners);
            }
        }
    }

    private void onEvent(VersionedEvent event) {
        if (!topic.equals(event.topic()) || !ownerReferences.containsKey(event.ownerKey())) {
            return;
        }
        subscriber.onEvent(event);
    }

    private void replay(Map<String, Long> knownRevisions, Set<String> ownerKeys) {
        replayAttempts.incrementAndGet();
        bus.replay(topic, knownRevisions, ownerKeys, new RpcCallback<>() {
            @Override
            public void success(EventReplayResult response) {
                repair(response);
            }

            @Override
            public void failure(Throwable error) {
                replayFailures.incrementAndGet();
            }
        });
    }

    private void repair(EventReplayResult response) {
        if (response.unavailableOwnerKeys().isEmpty()) {
            return;
        }
        repairOwners(response.unavailableOwnerKeys());
    }

    private void repairOwners(Set<String> ownerKeys) {
        repairRequests.incrementAndGet();
        repairOwnerCount.addAndGet(ownerKeys.size());
        try {
            repairExecutor.execute(() -> repairNow(ownerKeys));
        } catch (RuntimeException e) {
            repairFailures.incrementAndGet();
        }
    }

    private void repairNow(Set<String> ownerKeys) {
        try {
            repairer.repair(topic, ownerKeys);
        } catch (RuntimeException e) {
            repairFailures.incrementAndGet();
        }
    }

    private Map<String, Long> watchedRevisions() {
        Map<String, Long> revisions = new HashMap<>();
        ownerReferences.keySet().forEach(ownerKey -> revisions.put(ownerKey, revisionReader.applyAsLong(ownerKey)));
        return revisions;
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

    private static void validateOwnerKey(String ownerKey) {
        Objects.requireNonNull(ownerKey, "ownerKey");
        if (ownerKey.isBlank()) {
            throw new IllegalArgumentException("ownerKey must not be blank");
        }
    }

    private record UnwatchResult(boolean counted, boolean unsubscribe) {
    }
}
