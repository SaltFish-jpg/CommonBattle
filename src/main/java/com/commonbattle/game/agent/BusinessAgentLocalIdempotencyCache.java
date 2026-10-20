package com.commonbattle.game.agent;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.agent.AgentIdentity;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

final class BusinessAgentLocalIdempotencyCache {
    private final BusinessAgentIdempotencyConfig config;
    private final Clock clock;
    private final LinkedHashMap<Key, Entry> entries;
    private final LongAdder startedRequests = new LongAdder();
    private final LongAdder joinedInFlightRequests = new LongAdder();
    private final LongAdder completedCacheHits = new LongAdder();
    private final LongAdder expiredEntries = new LongAdder();
    private final LongAdder evictedEntries = new LongAdder();
    private final LongAdder completedSuccesses = new LongAdder();
    private final LongAdder completedFailures = new LongAdder();
    private final LongAdder abandonedInFlightRequests = new LongAdder();

    BusinessAgentLocalIdempotencyCache(BusinessAgentIdempotencyConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.entries = new LinkedHashMap<>(Math.max(1, config.capacity()), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
                boolean remove = BusinessAgentLocalIdempotencyCache.this.config.capacity() > 0
                        && size() > BusinessAgentLocalIdempotencyCache.this.config.capacity()
                        && eldest.getValue().completed();
                if (remove) {
                    evictedEntries.increment();
                }
                return remove;
            }
        };
    }

    boolean enabled() {
        return config.capacity() > 0;
    }

    synchronized Begin begin(Key key, Waiter waiter) {
        Entry entry = entries.get(key);
        if (entry == null) {
            entries.put(key, new Entry(waiter));
            startedRequests.increment();
            return Begin.started();
        }
        if (entry.completed()) {
            if (expired(entry)) {
                entries.put(key, new Entry(waiter));
                expiredEntries.increment();
                startedRequests.increment();
                return Begin.started();
            }
            completedCacheHits.increment();
            return Begin.replay(entry.result);
        }
        entry.waiters.add(waiter);
        joinedInFlightRequests.increment();
        return Begin.joined();
    }

    synchronized List<Waiter> complete(Key key, Result result) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return List.of();
        }
        entry.result = result;
        entry.completedAtMillis = clock.millis();
        List<Waiter> waiters = List.copyOf(entry.waiters);
        entry.waiters.clear();
        if (result.success()) {
            completedSuccesses.increment();
        } else {
            completedFailures.increment();
        }
        return waiters;
    }

    synchronized void abandon(Key key) {
        Entry entry = entries.get(key);
        if (entry != null && !entry.completed() && entries.remove(key, entry)) {
            abandonedInFlightRequests.increment();
        }
    }

    synchronized BusinessAgentLocalIdempotencyStats stats() {
        return new BusinessAgentLocalIdempotencyStats(
                config.capacity(),
                config.ttl().toMillis(),
                entries.size(),
                startedRequests.sum(),
                joinedInFlightRequests.sum(),
                completedCacheHits.sum(),
                expiredEntries.sum(),
                evictedEntries.sum(),
                completedSuccesses.sum(),
                completedFailures.sum(),
                abandonedInFlightRequests.sum()
        );
    }

    private boolean expired(Entry entry) {
        return !config.ttl().isZero()
                && clock.millis() - entry.completedAtMillis >= config.ttl().toMillis();
    }

    record Key(String requesterActorId, AgentIdentity target, String operation, String idempotencyKey) {
        Key {
            Objects.requireNonNull(requesterActorId, "requesterActorId");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        }
    }

    record Result(boolean success, Object response, Throwable error) {
        static Result success(Object response) {
            return new Result(true, response, null);
        }

        static Result failure(Throwable error) {
            return new Result(false, null, Objects.requireNonNull(error, "error"));
        }
    }

    interface Waiter {
        void complete(ActorContext context, Result result);
    }

    record Begin(State state, Result result) {
        static Begin started() {
            return new Begin(State.STARTED, null);
        }

        static Begin joined() {
            return new Begin(State.JOINED, null);
        }

        static Begin replay(Result result) {
            return new Begin(State.REPLAY, result);
        }
    }

    enum State {
        STARTED,
        JOINED,
        REPLAY
    }

    private static final class Entry {
        private final List<Waiter> waiters = new ArrayList<>();
        private Result result;
        private long completedAtMillis;

        private Entry(Waiter first) {
            waiters.add(first);
        }

        private boolean completed() {
            return result != null;
        }
    }
}
