package com.commonbattle.observability;

import com.commonbattle.actor.ActorSlowTask;
import com.commonbattle.actor.ActorSlowTaskSink;
import com.commonbattle.actor.ActorTaskCategory;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内存 Actor 慢任务日志。
 * 固定窗口保留最近慢任务样本，同时维护全量聚合计数，供健康检查和运维查询使用。
 */
public final class InMemoryActorSlowTaskLog implements ActorSlowTaskSink, ActorSlowTaskView {
    public static final int DEFAULT_CAPACITY = 128;

    private final int capacity;
    private final Clock clock;
    private final ArrayDeque<ActorSlowTaskRecord> records;
    private final EnumMap<ActorTaskCategory, Long> byCategory = new EnumMap<>(ActorTaskCategory.class);
    private long recorded;
    private long dropped;
    private long maxElapsedMillis;
    private String maxElapsedActorId = "";
    private ActorTaskCategory maxElapsedCategory = ActorTaskCategory.DEFAULT;

    public InMemoryActorSlowTaskLog() {
        this(DEFAULT_CAPACITY, Clock.systemUTC());
    }

    public InMemoryActorSlowTaskLog(int capacity) {
        this(capacity, Clock.systemUTC());
    }

    public InMemoryActorSlowTaskLog(int capacity, Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.records = new ArrayDeque<>(capacity);
        for (ActorTaskCategory category : ActorTaskCategory.values()) {
            byCategory.put(category, 0L);
        }
    }

    @Override
    public void accept(ActorSlowTask slowTask) {
        Objects.requireNonNull(slowTask, "slowTask");
        record(new ActorSlowTaskRecord(
                slowTask.actor().id(),
                slowTask.category(),
                slowTask.elapsed().toMillis(),
                slowTask.threshold().toMillis(),
                clock.instant()
        ));
    }

    @Override
    public synchronized ActorSlowTaskStats actorSlowTaskStats() {
        return new ActorSlowTaskStats(records.size(), recorded, dropped, maxElapsedMillis, maxElapsedActorId,
                maxElapsedCategory, byCategory);
    }

    @Override
    public synchronized List<ActorSlowTaskRecord> recentActorSlowTasks() {
        return List.copyOf(records);
    }

    public synchronized int size() {
        return records.size();
    }

    private synchronized void record(ActorSlowTaskRecord record) {
        if (records.size() == capacity) {
            records.removeFirst();
            dropped++;
        }
        records.addLast(record);
        recorded++;
        byCategory.merge(record.category(), 1L, Long::sum);
        if (record.elapsedMillis() > maxElapsedMillis) {
            maxElapsedMillis = record.elapsedMillis();
            maxElapsedActorId = record.actorId();
            maxElapsedCategory = record.category();
        }
    }
}
