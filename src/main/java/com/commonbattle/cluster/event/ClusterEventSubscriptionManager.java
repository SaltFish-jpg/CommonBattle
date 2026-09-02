package com.commonbattle.cluster.event;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.game.event.VersionedEventSubscriber;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨服事件订阅恢复管理器。
 * 服务启动、重连或中心事件服务重建后，用它统一补订阅并基于本地游标重放历史事件。
 */
public final class ClusterEventSubscriptionManager implements AutoCloseable {
    private final ClusterVersionedEventBus bus;
    private final Executor repairExecutor;
    private final CopyOnWriteArrayList<Registration> registrations = new CopyOnWriteArrayList<>();
    private final Map<Registration, AutoCloseable> activeSubscriptions = new ConcurrentHashMap<>();
    private final AtomicLong subscribeAttempts = new AtomicLong();
    private final AtomicLong subscribeFailures = new AtomicLong();
    private final AtomicLong replayAttempts = new AtomicLong();
    private final AtomicLong replayFailures = new AtomicLong();
    private final AtomicLong replayDelivered = new AtomicLong();
    private final AtomicLong replayUnavailableOwners = new AtomicLong();
    private final AtomicLong replayRepairRequests = new AtomicLong();
    private final AtomicLong replayRepairOwnerCount = new AtomicLong();
    private final AtomicLong replayRepairFailures = new AtomicLong();
    private final AtomicLong cursorFailures = new AtomicLong();
    private volatile boolean started;

    public ClusterEventSubscriptionManager(ClusterVersionedEventBus bus) {
        this(bus, Runnable::run);
    }

    public ClusterEventSubscriptionManager(ClusterVersionedEventBus bus, Executor repairExecutor) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.repairExecutor = Objects.requireNonNull(repairExecutor, "repairExecutor");
    }

    public AutoCloseable register(String topic, VersionedEventSubscriber subscriber, SubscriptionCursor cursor) {
        return register(topic, subscriber, cursor, EventReplayRepairer.noop());
    }

    public AutoCloseable register(
            String topic,
            VersionedEventSubscriber subscriber,
            SubscriptionCursor cursor,
            EventReplayRepairer repairer
    ) {
        Registration registration = new Registration(topic, subscriber, cursor, repairer);
        registrations.add(registration);
        if (started) {
            resubscribe(registration);
        }
        return () -> {
            registrations.remove(registration);
            closeOne(registration);
        };
    }

    public AutoCloseable register(String topic, VersionedEventSubscriber subscriber) {
        return register(topic, subscriber, SubscriptionCursor.empty());
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        registrations.forEach(this::resubscribe);
    }

    public synchronized void recover() {
        if (!started) {
            start();
            return;
        }
        registrations.forEach(this::resubscribe);
    }

    public ClusterEventSubscriptionStats stats() {
        return new ClusterEventSubscriptionStats(
                registrations.size(),
                activeSubscriptions.size(),
                subscribeAttempts.get(),
                subscribeFailures.get(),
                replayAttempts.get(),
                replayFailures.get(),
                replayDelivered.get(),
                replayUnavailableOwners.get(),
                replayRepairRequests.get(),
                replayRepairOwnerCount.get(),
                replayRepairFailures.get(),
                cursorFailures.get()
        );
    }

    private void resubscribe(Registration registration) {
        closeOne(registration);
        subscribeAttempts.incrementAndGet();
        try {
            AutoCloseable subscription = bus.subscribe(registration.topic(), registration.subscriber());
            activeSubscriptions.put(registration, subscription);
            replay(registration);
        } catch (RuntimeException e) {
            subscribeFailures.incrementAndGet();
        }
    }

    private void replay(Registration registration) {
        Map<String, Long> cursor = cursor(registration);
        replayAttempts.incrementAndGet();
        bus.replay(registration.topic(), cursor, registration.cursor().ownerKeys(), new RpcCallback<>() {
            @Override
            public void success(EventReplayResult response) {
                replayDelivered.addAndGet(response.delivered());
                replayUnavailableOwners.addAndGet(response.unavailableOwners());
                repair(registration, response);
            }

            @Override
            public void failure(Throwable error) {
                replayFailures.incrementAndGet();
            }
        });
    }

    private void repair(Registration registration, EventReplayResult response) {
        if (response.unavailableOwners() == 0) {
            return;
        }
        replayRepairRequests.incrementAndGet();
        replayRepairOwnerCount.addAndGet(response.unavailableOwners());
        try {
            Set<String> ownerKeys = response.unavailableOwnerKeys();
            repairExecutor.execute(() -> repairNow(registration, ownerKeys));
        } catch (RuntimeException e) {
            replayRepairFailures.incrementAndGet();
        }
    }

    private void repairNow(Registration registration, Set<String> ownerKeys) {
        try {
            registration.repairer().repair(registration.topic(), ownerKeys);
        } catch (RuntimeException e) {
            replayRepairFailures.incrementAndGet();
        }
    }

    private Map<String, Long> cursor(Registration registration) {
        try {
            return Map.copyOf(registration.cursor().knownRevisions());
        } catch (RuntimeException e) {
            cursorFailures.incrementAndGet();
            return Map.of();
        }
    }

    private void closeOne(Registration registration) {
        AutoCloseable subscription = activeSubscriptions.remove(registration);
        if (subscription == null) {
            return;
        }
        try {
            subscription.close();
        } catch (Exception ignored) {
            subscribeFailures.incrementAndGet();
        }
    }

    @Override
    public synchronized void close() {
        started = false;
        registrations.forEach(this::closeOne);
    }

    private record Registration(
            String topic,
            VersionedEventSubscriber subscriber,
            SubscriptionCursor cursor,
            EventReplayRepairer repairer
    ) {
        private Registration {
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(subscriber, "subscriber");
            Objects.requireNonNull(cursor, "cursor");
            Objects.requireNonNull(repairer, "repairer");
            if (topic.isBlank()) {
                throw new IllegalArgumentException("event topic must not be blank");
            }
        }
    }
}
