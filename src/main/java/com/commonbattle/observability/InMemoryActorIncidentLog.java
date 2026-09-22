package com.commonbattle.observability;

import com.commonbattle.actor.ActorFailure;
import com.commonbattle.actor.ActorFailureHandler;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.DeadLetter;
import com.commonbattle.actor.DeadLetterSink;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内存 Actor incident 日志。
 * 同时接收死信和任务异常，用一个固定窗口保留最近样本，并维护全量聚合计数。
 */
public final class InMemoryActorIncidentLog implements ActorFailureHandler, DeadLetterSink, ActorIncidentView {
    public static final int DEFAULT_CAPACITY = 128;

    private final int capacity;
    private final Clock clock;
    private final ArrayDeque<ActorIncidentRecord> records;
    private final EnumMap<ActorIncidentKind, Long> byKind = new EnumMap<>(ActorIncidentKind.class);
    private final EnumMap<ActorTaskCategory, Long> byCategory = new EnumMap<>(ActorTaskCategory.class);
    private final Map<String, Long> byReason = new HashMap<>();
    private long recorded;
    private long deadLetters;
    private long poisonMessages;
    private long dropped;

    public InMemoryActorIncidentLog() {
        this(DEFAULT_CAPACITY, Clock.systemUTC());
    }

    public InMemoryActorIncidentLog(int capacity) {
        this(capacity, Clock.systemUTC());
    }

    public InMemoryActorIncidentLog(int capacity, Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.records = new ArrayDeque<>(capacity);
        for (ActorIncidentKind kind : ActorIncidentKind.values()) {
            byKind.put(kind, 0L);
        }
        for (ActorTaskCategory category : ActorTaskCategory.values()) {
            byCategory.put(category, 0L);
        }
    }

    @Override
    public void accept(DeadLetter deadLetter) {
        Objects.requireNonNull(deadLetter, "deadLetter");
        ActorTask task = deadLetter.task();
        record(new ActorIncidentRecord(
                ActorIncidentKind.DEAD_LETTER,
                deadLetter.target().id(),
                categoryOf(task),
                deadLetter.reason().name(),
                "",
                "",
                clock.instant()
        ));
    }

    @Override
    public void onFailure(ActorFailure failure) {
        Objects.requireNonNull(failure, "failure");
        Throwable error = failure.error();
        record(new ActorIncidentRecord(
                ActorIncidentKind.POISON_MESSAGE,
                failure.actor().id(),
                categoryOf(failure.task()),
                "TASK_FAILED",
                error == null ? "" : error.getClass().getName(),
                error == null ? "" : error.getMessage(),
                clock.instant()
        ));
    }

    @Override
    public synchronized ActorIncidentStats actorIncidentStats() {
        return new ActorIncidentStats(
                records.size(),
                recorded,
                deadLetters,
                poisonMessages,
                dropped,
                byKind,
                byCategory,
                byReason
        );
    }

    @Override
    public synchronized List<ActorIncidentRecord> recentActorIncidents() {
        return List.copyOf(records);
    }

    public synchronized int size() {
        return records.size();
    }

    private synchronized void record(ActorIncidentRecord record) {
        if (records.size() == capacity) {
            records.removeFirst();
            dropped++;
        }
        records.addLast(record);
        recorded++;
        if (record.kind() == ActorIncidentKind.DEAD_LETTER) {
            deadLetters++;
        } else {
            poisonMessages++;
        }
        byKind.merge(record.kind(), 1L, Long::sum);
        byCategory.merge(record.category(), 1L, Long::sum);
        byReason.merge(record.reason(), 1L, Long::sum);
    }

    private static ActorTaskCategory categoryOf(ActorTask task) {
        if (task == null || task.category() == null) {
            return ActorTaskCategory.DEFAULT;
        }
        return task.category();
    }
}
