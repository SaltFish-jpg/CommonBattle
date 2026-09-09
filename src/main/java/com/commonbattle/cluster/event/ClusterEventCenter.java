package com.commonbattle.cluster.event;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.network.ClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcResponder;
import com.commonbattle.game.event.VersionedEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨服事件中心。
 * 负责维护 topic -> subscriber 的订阅表，发布事件时按 ServiceId 推送给订阅服务。
 */
public final class ClusterEventCenter {
    public static final int DEFAULT_HISTORY_LIMIT = 10_000;

    private final ServiceDescriptor local;
    private final ClusterTransport transport;
    private final ClusterEventHistoryPolicy historyPolicy;
    private final Clock clock;
    private final Duration subscriptionLeaseTtl;
    private final Map<String, Set<ServiceId>> topicSubscribers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Set<ServiceId>>> ownerSubscribers = new ConcurrentHashMap<>();
    private final Map<SubscriptionKey, Map<ServiceId, Instant>> subscriptionLeases = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<VersionedEvent>> history = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> publishedByTopic = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> droppedByTopic = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> deliveryFailuresByTopic = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> expiredSubscriptionsByTopic = new ConcurrentHashMap<>();

    public ClusterEventCenter(ServiceDescriptor local, ClusterTransport transport, ClusterRpcGateway gateway) {
        this(local, transport, gateway, DEFAULT_HISTORY_LIMIT);
    }

    public ClusterEventCenter(
            ServiceDescriptor local,
            ClusterTransport transport,
            ClusterRpcGateway gateway,
            int historyLimit
    ) {
        this(local, transport, gateway, ClusterEventHistoryPolicy.fixed(historyLimit));
    }

    public ClusterEventCenter(
            ServiceDescriptor local,
            ClusterTransport transport,
            ClusterRpcGateway gateway,
            ClusterEventHistoryPolicy historyPolicy
    ) {
        this(local, transport, gateway, historyPolicy, Clock.systemUTC(), Duration.ofSeconds(15));
    }

    public ClusterEventCenter(
            ServiceDescriptor local,
            ClusterTransport transport,
            ClusterRpcGateway gateway,
            ClusterEventHistoryPolicy historyPolicy,
            Clock clock,
            Duration subscriptionLeaseTtl
    ) {
        this.local = Objects.requireNonNull(local, "local");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.historyPolicy = Objects.requireNonNull(historyPolicy, "historyPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.subscriptionLeaseTtl = positive(subscriptionLeaseTtl, "subscriptionLeaseTtl");
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(ClusterEventOperations.SUBSCRIBE, this::subscribe);
        gateway.handle(ClusterEventOperations.UNSUBSCRIBE, this::unsubscribe);
        gateway.handle(ClusterEventOperations.PUBLISH, this::publish);
        gateway.handle(ClusterEventOperations.REPLAY, this::replay);
    }

    public Set<ServiceId> subscribers(String topic) {
        Set<ServiceId> subscribers = new HashSet<>(topicSubscribers.getOrDefault(topic, Set.of()));
        ownerSubscribers.getOrDefault(topic, Map.of()).values().forEach(subscribers::addAll);
        return Set.copyOf(subscribers);
    }

    public int historySize(String topic) {
        ArrayDeque<VersionedEvent> topicHistory = history.get(topic);
        if (topicHistory == null) {
            return 0;
        }
        synchronized (topicHistory) {
            return topicHistory.size();
        }
    }

    public ClusterEventCenterStats stats() {
        Set<String> topics = new HashSet<>();
        topics.addAll(history.keySet());
        topics.addAll(topicSubscribers.keySet());
        topics.addAll(ownerSubscribers.keySet());
        topics.addAll(publishedByTopic.keySet());
        topics.addAll(droppedByTopic.keySet());
        topics.addAll(deliveryFailuresByTopic.keySet());
        topics.addAll(expiredSubscriptionsByTopic.keySet());
        Map<String, ClusterEventTopicStats> topicStats = new HashMap<>();
        for (String topic : topics) {
            topicStats.put(topic, topicStats(topic));
        }
        return new ClusterEventCenterStats(topicStats);
    }

    public void publishLocal(VersionedEvent event) {
        deliver(new EventPublishRequest(Objects.requireNonNull(event, "event")));
    }

    private void subscribe(ClusterEnvelope request, RpcResponder responder) {
        EventSubscribeRequest payload = (EventSubscribeRequest) request.payload();
        if (payload.ownerKeys().isEmpty()) {
            topicSubscribers.computeIfAbsent(payload.topic(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(payload.subscriber());
            renewLease(new SubscriptionKey(payload.topic(), ""), payload.subscriber(), payload.leaseTtl());
        } else {
            Map<String, Set<ServiceId>> topicOwners = ownerSubscribers.computeIfAbsent(
                    payload.topic(),
                    ignored -> new ConcurrentHashMap<>()
            );
            payload.ownerKeys().forEach(ownerKey -> topicOwners.computeIfAbsent(ownerKey, ignored -> ConcurrentHashMap.newKeySet())
                    .add(payload.subscriber()));
            payload.ownerKeys().forEach(ownerKey ->
                    renewLease(new SubscriptionKey(payload.topic(), ownerKey), payload.subscriber(), payload.leaseTtl()));
        }
        responder.success("subscribed");
    }

    private void unsubscribe(ClusterEnvelope request, RpcResponder responder) {
        EventUnsubscribeRequest payload = (EventUnsubscribeRequest) request.payload();
        if (payload.ownerKeys().isEmpty()) {
            topicSubscribers.getOrDefault(payload.topic(), Set.of()).remove(payload.subscriber());
            removeLease(new SubscriptionKey(payload.topic(), ""), payload.subscriber());
        } else {
            Map<String, Set<ServiceId>> topicOwners = ownerSubscribers.getOrDefault(payload.topic(), Map.of());
            payload.ownerKeys().forEach(ownerKey -> {
                topicOwners.getOrDefault(ownerKey, Set.of()).remove(payload.subscriber());
                removeLease(new SubscriptionKey(payload.topic(), ownerKey), payload.subscriber());
            });
        }
        responder.success("unsubscribed");
    }

    private void publish(ClusterEnvelope request, RpcResponder responder) {
        EventPublishRequest payload = (EventPublishRequest) request.payload();
        deliver(payload);
        responder.success("published");
    }

    private void replay(ClusterEnvelope request, RpcResponder responder) {
        EventReplayRequest payload = (EventReplayRequest) request.payload();
        List<VersionedEvent> retained = retained(payload.topic());
        Set<String> unavailableOwnerKeys = unavailableOwnerKeys(retained, payload.knownRevisions());
        int delivered = 0;
        for (VersionedEvent event : retained) {
            if (!payload.ownerKeys().isEmpty() && !payload.ownerKeys().contains(event.ownerKey())) {
                continue;
            }
            long knownRevision = payload.knownRevisions().getOrDefault(event.ownerKey(), 0L);
            if (event.revision() > knownRevision) {
                send(payload.subscriber(), event);
                delivered++;
            }
        }
        responder.success(new EventReplayResult(delivered, unavailableOwnerKeys.size(), unavailableOwnerKeys));
    }

    private void deliver(EventPublishRequest payload) {
        var event = payload.event();
        publishedByTopic.computeIfAbsent(event.topic(), ignored -> new AtomicLong()).incrementAndGet();
        record(event);
        subscribers(event.topic(), event.ownerKey()).forEach(subscriber -> sendSafely(subscriber, event));
    }

    public int expireSubscriptions(Instant now) {
        Objects.requireNonNull(now, "now");
        int expired = 0;
        for (Map.Entry<SubscriptionKey, Map<ServiceId, Instant>> leaseEntry : subscriptionLeases.entrySet()) {
            SubscriptionKey key = leaseEntry.getKey();
            Map<ServiceId, Instant> leases = leaseEntry.getValue();
            for (Map.Entry<ServiceId, Instant> subscriberLease : List.copyOf(leases.entrySet())) {
                if (!subscriberLease.getValue().isAfter(now)
                        && leases.remove(subscriberLease.getKey(), subscriberLease.getValue())) {
                    removeSubscriber(key, subscriberLease.getKey());
                    expired++;
                    expiredSubscriptionsByTopic.computeIfAbsent(key.topic(), ignored -> new AtomicLong())
                            .incrementAndGet();
                }
            }
        }
        return expired;
    }

    private void send(ServiceId subscriber, VersionedEvent event) {
        transport.send(subscriber, new ClusterEnvelope(
                0,
                local.id(),
                subscriber,
                ClusterEventOperations.DELIVER,
                new EventDeliverRequest(event)
        ));
    }

    private void sendSafely(ServiceId subscriber, VersionedEvent event) {
        try {
            send(subscriber, event);
        } catch (RuntimeException e) {
            deliveryFailuresByTopic.computeIfAbsent(event.topic(), ignored -> new AtomicLong()).incrementAndGet();
        }
    }

    private void renewLease(SubscriptionKey key, ServiceId subscriber, Duration requestedTtl) {
        Duration ttl = requestedTtl.isZero() ? subscriptionLeaseTtl : requestedTtl;
        subscriptionLeases.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>())
                .put(subscriber, clock.instant().plus(ttl));
    }

    private void removeLease(SubscriptionKey key, ServiceId subscriber) {
        Map<ServiceId, Instant> leases = subscriptionLeases.get(key);
        if (leases == null) {
            return;
        }
        leases.remove(subscriber);
    }

    private void removeSubscriber(SubscriptionKey key, ServiceId subscriber) {
        if (key.ownerKey().isBlank()) {
            topicSubscribers.getOrDefault(key.topic(), Set.of()).remove(subscriber);
            return;
        }
        ownerSubscribers.getOrDefault(key.topic(), Map.of())
                .getOrDefault(key.ownerKey(), Set.of())
                .remove(subscriber);
    }

    private void record(VersionedEvent event) {
        ArrayDeque<VersionedEvent> topicHistory = history.computeIfAbsent(event.topic(), ignored -> new ArrayDeque<>());
        int historyLimit = historyPolicy.limitOf(event.topic());
        synchronized (topicHistory) {
            while (topicHistory.size() >= historyLimit) {
                topicHistory.removeFirst();
                droppedByTopic.computeIfAbsent(event.topic(), ignored -> new AtomicLong()).incrementAndGet();
            }
            topicHistory.addLast(event);
        }
    }

    private List<VersionedEvent> retained(String topic) {
        ArrayDeque<VersionedEvent> topicHistory = history.get(topic);
        if (topicHistory == null) {
            return List.of();
        }
        synchronized (topicHistory) {
            return new ArrayList<>(topicHistory);
        }
    }

    private Set<String> unavailableOwnerKeys(List<VersionedEvent> retained, Map<String, Long> knownRevisions) {
        Map<String, Long> firstRetainedRevision = new HashMap<>();
        for (VersionedEvent event : retained) {
            firstRetainedRevision.merge(event.ownerKey(), event.revision(), Math::min);
        }
        Set<String> unavailable = new HashSet<>();
        for (Map.Entry<String, Long> entry : knownRevisions.entrySet()) {
            Long firstRevision = firstRetainedRevision.get(entry.getKey());
            if (firstRevision != null && firstRevision > entry.getValue() + 1) {
                unavailable.add(entry.getKey());
            }
        }
        return unavailable;
    }

    private ClusterEventTopicStats topicStats(String topic) {
        List<VersionedEvent> retained = retained(topic);
        Set<String> retainedOwners = new HashSet<>();
        long minRevision = Long.MAX_VALUE;
        long maxRevision = 0;
        for (VersionedEvent event : retained) {
            retainedOwners.add(event.ownerKey());
            minRevision = Math.min(minRevision, event.revision());
            maxRevision = Math.max(maxRevision, event.revision());
        }
        return new ClusterEventTopicStats(
                topic,
                historyPolicy.limitOf(topic),
                retained.size(),
                retainedOwners.size(),
                subscribers(topic).size(),
                publishedByTopic.getOrDefault(topic, new AtomicLong()).get(),
                droppedByTopic.getOrDefault(topic, new AtomicLong()).get(),
                deliveryFailuresByTopic.getOrDefault(topic, new AtomicLong()).get(),
                expiredSubscriptionsByTopic.getOrDefault(topic, new AtomicLong()).get(),
                minRevision == Long.MAX_VALUE ? 0 : minRevision,
                maxRevision
        );
    }

    private Set<ServiceId> subscribers(String topic, String ownerKey) {
        Set<ServiceId> subscribers = new HashSet<>(topicSubscribers.getOrDefault(topic, Set.of()));
        subscribers.addAll(ownerSubscribers.getOrDefault(topic, Map.of()).getOrDefault(ownerKey, Set.of()));
        return subscribers;
    }

    private static Duration positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private record SubscriptionKey(String topic, String ownerKey) {
        private SubscriptionKey {
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(ownerKey, "ownerKey");
        }
    }
}
