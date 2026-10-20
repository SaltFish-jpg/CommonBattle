package com.commonbattle.cluster.event;

import com.commonbattle.game.event.VersionedEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 中心事件日志的内存实现，适用于测试和单进程开发环境。
 */
public final class InMemoryClusterEventJournal implements ClusterEventJournal {
    private final Map<ClusterEventJournalKey, VersionedEvent> events = new HashMap<>();
    private final Map<String, ArrayDeque<ClusterEventJournalKey>> orderByTopic = new HashMap<>();
    private final Map<OwnerStream, Long> highWaterMarks = new HashMap<>();

    @Override
    public synchronized ClusterEventJournalAppendResult appendAndTrim(VersionedEvent event, int historyLimit) {
        Objects.requireNonNull(event, "event");
        if (historyLimit <= 0) {
            throw new IllegalArgumentException("historyLimit must be positive");
        }
        ClusterEventJournalKey key = ClusterEventJournalKey.of(event);
        VersionedEvent existing = events.get(key);
        if (existing != null) {
            requireSameEvent(key, existing, event);
            return new ClusterEventJournalAppendResult(ClusterEventJournalAppendStatus.DUPLICATE, 0);
        }
        OwnerStream stream = new OwnerStream(event.topic(), event.ownerKey());
        if (event.revision() <= highWaterMarks.getOrDefault(stream, 0L)) {
            return new ClusterEventJournalAppendResult(ClusterEventJournalAppendStatus.DUPLICATE, 0);
        }
        events.put(key, event);
        highWaterMarks.put(stream, event.revision());
        ArrayDeque<ClusterEventJournalKey> order = orderByTopic.computeIfAbsent(
                event.topic(), ignored -> new ArrayDeque<>()
        );
        order.addLast(key);
        int trimmed = 0;
        while (order.size() > historyLimit) {
            events.remove(order.removeFirst());
            trimmed++;
        }
        return new ClusterEventJournalAppendResult(ClusterEventJournalAppendStatus.APPENDED, trimmed);
    }

    @Override
    public synchronized List<VersionedEvent> retained(String topic) {
        Objects.requireNonNull(topic, "topic");
        ArrayDeque<ClusterEventJournalKey> order = orderByTopic.get(topic);
        if (order == null) {
            return List.of();
        }
        List<VersionedEvent> retained = new ArrayList<>(order.size());
        order.forEach(key -> retained.add(events.get(key)));
        return List.copyOf(retained);
    }

    @Override
    public synchronized long highWaterMark(String topic, String ownerKey) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(ownerKey, "ownerKey");
        return highWaterMarks.getOrDefault(new OwnerStream(topic, ownerKey), 0L);
    }

    @Override
    public synchronized Set<String> topics() {
        Set<String> topics = new HashSet<>();
        orderByTopic.forEach((topic, order) -> {
            if (!order.isEmpty()) {
                topics.add(topic);
            }
        });
        return Set.copyOf(topics);
    }

    private static void requireSameEvent(
            ClusterEventJournalKey key,
            VersionedEvent existing,
            VersionedEvent candidate
    ) {
        if (!existing.equals(candidate)) {
            throw new IllegalStateException("Event journal key collision for " + key);
        }
    }

    private record OwnerStream(String topic, String ownerKey) {
    }
}
