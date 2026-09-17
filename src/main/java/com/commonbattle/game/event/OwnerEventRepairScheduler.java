package com.commonbattle.game.event;

import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * owner 事件投影修复调度器。
 * 业务 Agent 可以高频请求修复，调度器按 ownerKey 去重并限流下发到底层订阅。
 */
public final class OwnerEventRepairScheduler
        implements OwnerEventInterestControl, OwnerEventRepairSchedulerView, OwnerEventRepairIsolationAdmin,
        AutoCloseable {
    public static final int DEFAULT_MAX_BATCH_SIZE = 64;
    public static final int DEFAULT_PRIORITY = 0;

    private final OwnerEventInterestControl delegate;
    private final int maxBatchSize;
    private final int defaultPriority;
    private final OwnerEventRepairBackoffPolicy backoffPolicy;
    private final OwnerEventRepairIsolationPolicy isolationPolicy;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final LongSupplier nanoTime;
    private final NavigableMap<Integer, LinkedHashSet<String>> pendingOwnersByPriority = new TreeMap<>();
    private final Map<String, Integer> ownerPriorities = new HashMap<>();
    private final Map<String, Integer> ownerFailureCounts = new HashMap<>();
    private final Map<String, IsolatedOwner> isolatedOwners = new HashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final LongAdder repairRequests = new LongAdder();
    private final LongAdder requestedOwners = new LongAdder();
    private final LongAdder enqueuedOwners = new LongAdder();
    private final LongAdder duplicateOwners = new LongAdder();
    private final LongAdder dispatchRuns = new LongAdder();
    private final LongAdder dispatchedOwners = new LongAdder();
    private final LongAdder failedRuns = new LongAdder();
    private final LongAdder skippedRuns = new LongAdder();
    private final LongAdder backoffSkips = new LongAdder();
    private final LongAdder isolatedOwnersTotal = new LongAdder();
    private final LongAdder isolationSkips = new LongAdder();
    private final LongAdder releasedIsolatedOwners = new LongAdder();
    private volatile ScheduledFuture<?> task;
    private volatile int consecutiveFailures;
    private volatile long backoffUntilNanos;
    private volatile boolean singleOwnerProbeMode;

    public OwnerEventRepairScheduler(OwnerEventInterestControl delegate, Duration interval) {
        this(delegate, interval, DEFAULT_MAX_BATCH_SIZE);
    }

    public OwnerEventRepairScheduler(
            OwnerEventInterestControl delegate,
            Duration interval,
            int maxBatchSize
    ) {
        this(delegate, interval, maxBatchSize, DEFAULT_PRIORITY);
    }

    public OwnerEventRepairScheduler(
            OwnerEventInterestControl delegate,
            Duration interval,
            int maxBatchSize,
            int defaultPriority
    ) {
        this(delegate, interval, maxBatchSize, defaultPriority, OwnerEventRepairBackoffPolicy.disabled());
    }

    public OwnerEventRepairScheduler(
            OwnerEventInterestControl delegate,
            Duration interval,
            int maxBatchSize,
            int defaultPriority,
            OwnerEventRepairBackoffPolicy backoffPolicy
    ) {
        this(delegate, interval, maxBatchSize, defaultPriority, backoffPolicy,
                OwnerEventRepairIsolationPolicy.disabled());
    }

    public OwnerEventRepairScheduler(
            OwnerEventInterestControl delegate,
            Duration interval,
            int maxBatchSize,
            int defaultPriority,
            OwnerEventRepairBackoffPolicy backoffPolicy,
            OwnerEventRepairIsolationPolicy isolationPolicy
    ) {
        this(delegate, interval, maxBatchSize, defaultPriority, backoffPolicy, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "owner-event-repair-scheduler");
            thread.setDaemon(true);
            return thread;
        }), true, System::nanoTime, isolationPolicy);
    }

    public OwnerEventRepairScheduler(
            OwnerEventInterestControl delegate,
            Duration interval,
            int maxBatchSize,
            ScheduledExecutorService scheduler
    ) {
        this(delegate, interval, maxBatchSize, DEFAULT_PRIORITY, scheduler);
    }

    public OwnerEventRepairScheduler(
            OwnerEventInterestControl delegate,
            Duration interval,
            int maxBatchSize,
            int defaultPriority,
            ScheduledExecutorService scheduler
    ) {
        this(delegate, interval, maxBatchSize, defaultPriority, OwnerEventRepairBackoffPolicy.disabled(), scheduler);
    }

    public OwnerEventRepairScheduler(
            OwnerEventInterestControl delegate,
            Duration interval,
            int maxBatchSize,
            int defaultPriority,
            OwnerEventRepairBackoffPolicy backoffPolicy,
            ScheduledExecutorService scheduler
    ) {
        this(delegate, interval, maxBatchSize, defaultPriority, backoffPolicy,
                OwnerEventRepairIsolationPolicy.disabled(), scheduler);
    }

    public OwnerEventRepairScheduler(
            OwnerEventInterestControl delegate,
            Duration interval,
            int maxBatchSize,
            int defaultPriority,
            OwnerEventRepairBackoffPolicy backoffPolicy,
            OwnerEventRepairIsolationPolicy isolationPolicy,
            ScheduledExecutorService scheduler
    ) {
        this(delegate, interval, maxBatchSize, defaultPriority, backoffPolicy, scheduler, false,
                System::nanoTime, isolationPolicy);
    }

    OwnerEventRepairScheduler(
            OwnerEventInterestControl delegate,
            Duration interval,
            int maxBatchSize,
            int defaultPriority,
            OwnerEventRepairBackoffPolicy backoffPolicy,
            OwnerEventRepairIsolationPolicy isolationPolicy,
            LongSupplier nanoTime
    ) {
        this(delegate, interval, maxBatchSize, defaultPriority, backoffPolicy,
                Executors.newSingleThreadScheduledExecutor(), true, nanoTime, isolationPolicy);
    }

    OwnerEventRepairScheduler(
            OwnerEventInterestControl delegate,
            Duration interval,
            int maxBatchSize,
            int defaultPriority,
            OwnerEventRepairBackoffPolicy backoffPolicy,
            LongSupplier nanoTime
    ) {
        this(delegate, interval, maxBatchSize, defaultPriority, backoffPolicy,
                OwnerEventRepairIsolationPolicy.disabled(), nanoTime);
    }

    private OwnerEventRepairScheduler(
            OwnerEventInterestControl delegate,
            Duration interval,
            int maxBatchSize,
            int defaultPriority,
            OwnerEventRepairBackoffPolicy backoffPolicy,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler,
            LongSupplier nanoTime,
            OwnerEventRepairIsolationPolicy isolationPolicy
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.interval = positive(interval, "interval");
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        if (defaultPriority < 0) {
            throw new IllegalArgumentException("defaultPriority must not be negative");
        }
        this.maxBatchSize = maxBatchSize;
        this.defaultPriority = defaultPriority;
        this.backoffPolicy = Objects.requireNonNull(backoffPolicy, "backoffPolicy");
        this.isolationPolicy = Objects.requireNonNull(isolationPolicy, "isolationPolicy");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public void watchOwner(String ownerKey) {
        delegate.watchOwner(ownerKey);
    }

    @Override
    public void watchOwners(Collection<String> ownerKeys) {
        delegate.watchOwners(ownerKeys);
    }

    @Override
    public void unwatchOwner(String ownerKey) {
        delegate.unwatchOwner(ownerKey);
        removePending(Set.of(ownerKey));
    }

    @Override
    public void unwatchOwners(Collection<String> ownerKeys) {
        delegate.unwatchOwners(ownerKeys);
        removePending(ownerKeys);
    }

    @Override
    public void requestRepairOwners(Collection<String> ownerKeys) {
        requestRepairOwners(ownerKeys, defaultPriority);
    }

    /**
     * 请求按指定优先级修复 owner 投影；数字越大越先被调度。
     */
    public void requestRepairOwners(Collection<String> ownerKeys, int priority) {
        if (priority < 0) {
            throw new IllegalArgumentException("priority must not be negative");
        }
        Objects.requireNonNull(ownerKeys, "ownerKeys");
        if (ownerKeys.isEmpty()) {
            return;
        }
        repairRequests.increment();
        requestedOwners.add(ownerKeys.size());
        synchronized (ownerPriorities) {
            releaseExpiredIsolations(nanoTime.getAsLong());
            for (String ownerKey : ownerKeys) {
                validateOwnerKey(ownerKey);
                if (isolatedOwners.containsKey(ownerKey)) {
                    isolationSkips.increment();
                    continue;
                }
                Integer oldPriority = ownerPriorities.get(ownerKey);
                if (oldPriority == null) {
                    ownerPriorities.put(ownerKey, priority);
                    pendingOwnersByPriority
                            .computeIfAbsent(priority, ignored -> new LinkedHashSet<>())
                            .add(ownerKey);
                    enqueuedOwners.increment();
                } else {
                    duplicateOwners.increment();
                    if (priority > oldPriority) {
                        moveOwnerPriority(ownerKey, oldPriority, priority);
                    }
                }
            }
        }
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(
                this::drainSafely,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public int drainOnce() {
        if (!inFlight.compareAndSet(false, true)) {
            skippedRuns.increment();
            return 0;
        }
        RepairBatch batch;
        try {
            if (backoffActive(nanoTime.getAsLong())) {
                backoffSkips.increment();
                return 0;
            }
            batch = pollBatch();
            if (batch.isEmpty()) {
                return 0;
            }
            dispatchRuns.increment();
            try {
                delegate.requestRepairOwners(batch.ownerKeys());
                dispatchedOwners.add(batch.size());
                recordSuccessfulBatch(batch);
                resetBackoff();
                return batch.size();
            } catch (RuntimeException e) {
                failedRuns.increment();
                recordFailedBatch(batch);
                applyBackoff();
                throw e;
            }
        } finally {
            inFlight.set(false);
        }
    }

    @Override
    public OwnerEventRepairSchedulerStats repairSchedulerStats() {
        synchronized (ownerPriorities) {
            releaseExpiredIsolations(nanoTime.getAsLong());
            return new OwnerEventRepairSchedulerStats(
                    ownerPriorities.size(),
                    defaultPriority,
                    highestPendingPriority(),
                    repairRequests.sum(),
                    requestedOwners.sum(),
                    enqueuedOwners.sum(),
                    duplicateOwners.sum(),
                    dispatchRuns.sum(),
                    dispatchedOwners.sum(),
                    failedRuns.sum(),
                    skippedRuns.sum(),
                    backoffSkips.sum(),
                    consecutiveFailures,
                    backoffActive(nanoTime.getAsLong()),
                    backoffRemainingMillis(nanoTime.getAsLong()),
                    isolatedOwners.size(),
                    isolatedOwnersTotal.sum(),
                    isolationSkips.sum(),
                    releasedIsolatedOwners.sum(),
                    singleOwnerProbeMode,
                    inFlight.get()
            );
        }
    }

    @Override
    public List<OwnerEventRepairIsolatedOwner> isolatedOwners() {
        synchronized (ownerPriorities) {
            long nowNanos = nanoTime.getAsLong();
            releaseExpiredIsolations(nowNanos);
            return isolatedOwners.entrySet().stream()
                    .map(entry -> new OwnerEventRepairIsolatedOwner(
                            entry.getKey(),
                            entry.getValue().priority(),
                            remainingIsolationMillis(entry.getValue(), nowNanos)
                    ))
                    .sorted(Comparator.comparing(OwnerEventRepairIsolatedOwner::ownerKey))
                    .toList();
        }
    }

    @Override
    public int releaseIsolatedOwner(String ownerKey) {
        validateOwnerKey(ownerKey);
        synchronized (ownerPriorities) {
            IsolatedOwner isolated = isolatedOwners.remove(ownerKey);
            if (isolated == null) {
                return 0;
            }
            enqueuePending(ownerKey, isolated.priority());
            releasedIsolatedOwners.increment();
            return 1;
        }
    }

    @Override
    public int releaseAllIsolatedOwners() {
        synchronized (ownerPriorities) {
            int released = isolatedOwners.size();
            if (released == 0) {
                return 0;
            }
            for (Map.Entry<String, IsolatedOwner> entry : isolatedOwners.entrySet()) {
                enqueuePending(entry.getKey(), entry.getValue().priority());
            }
            isolatedOwners.clear();
            releasedIsolatedOwners.add(released);
            return released;
        }
    }

    @Override
    public void close() {
        ScheduledFuture<?> scheduled = task;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        started.set(false);
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private void drainSafely() {
        try {
            drainOnce();
        } catch (RuntimeException ignored) {
        }
    }

    private RepairBatch pollBatch() {
        synchronized (ownerPriorities) {
            releaseExpiredIsolations(nanoTime.getAsLong());
            if (ownerPriorities.isEmpty()) {
                singleOwnerProbeMode = false;
                return RepairBatch.empty();
            }
            LinkedHashSet<String> batch = new LinkedHashSet<>();
            Map<String, Integer> batchPriorities = new HashMap<>();
            int effectiveMaxBatchSize = singleOwnerProbeMode ? 1 : maxBatchSize;
            while (!pendingOwnersByPriority.isEmpty() && batch.size() < effectiveMaxBatchSize) {
                Map.Entry<Integer, LinkedHashSet<String>> entry = pendingOwnersByPriority.lastEntry();
                var iterator = entry.getValue().iterator();
                while (iterator.hasNext() && batch.size() < effectiveMaxBatchSize) {
                    String ownerKey = iterator.next();
                    batch.add(ownerKey);
                    batchPriorities.put(ownerKey, entry.getKey());
                    iterator.remove();
                    ownerPriorities.remove(ownerKey);
                }
                if (entry.getValue().isEmpty()) {
                    pendingOwnersByPriority.remove(entry.getKey());
                }
            }
            return new RepairBatch(batch, batchPriorities);
        }
    }

    private void requeue(RepairBatch batch) {
        synchronized (ownerPriorities) {
            for (String ownerKey : batch.ownerKeys()) {
                if (!isolatedOwners.containsKey(ownerKey)) {
                    enqueuePending(ownerKey, batch.priorityOf(ownerKey));
                }
            }
        }
    }

    private void recordSuccessfulBatch(RepairBatch batch) {
        synchronized (ownerPriorities) {
            for (String ownerKey : batch.ownerKeys()) {
                ownerFailureCounts.remove(ownerKey);
            }
            if (ownerPriorities.isEmpty()) {
                singleOwnerProbeMode = false;
            }
        }
    }

    private void recordFailedBatch(RepairBatch batch) {
        if (batch.size() > 1 && isolationPolicy.enabled()) {
            singleOwnerProbeMode = true;
            requeue(batch);
            return;
        }
        String ownerKey = batch.onlyOwnerKey();
        if (ownerKey == null || !isolationPolicy.enabled()) {
            requeue(batch);
            return;
        }
        synchronized (ownerPriorities) {
            int failures = ownerFailureCounts.merge(ownerKey, 1, Integer::sum);
            if (failures >= isolationPolicy.maxFailures()) {
                isolateOwner(ownerKey, batch.priorityOf(ownerKey));
            } else {
                enqueuePending(ownerKey, batch.priorityOf(ownerKey));
            }
        }
    }

    private void isolateOwner(String ownerKey, int priority) {
        ownerFailureCounts.remove(ownerKey);
        isolatedOwners.put(ownerKey, new IsolatedOwner(priority,
                safeAdd(nanoTime.getAsLong(), isolationPolicy.duration().toNanos())));
        isolatedOwnersTotal.increment();
    }

    private void moveOwnerPriority(String ownerKey, int oldPriority, int newPriority) {
        LinkedHashSet<String> oldBucket = pendingOwnersByPriority.get(oldPriority);
        if (oldBucket != null) {
            oldBucket.remove(ownerKey);
            if (oldBucket.isEmpty()) {
                pendingOwnersByPriority.remove(oldPriority);
            }
        }
        ownerPriorities.put(ownerKey, newPriority);
        pendingOwnersByPriority
                .computeIfAbsent(newPriority, ignored -> new LinkedHashSet<>())
                .add(ownerKey);
    }

    private void enqueuePending(String ownerKey, int priority) {
        Integer oldPriority = ownerPriorities.get(ownerKey);
        if (oldPriority != null) {
            if (priority > oldPriority) {
                moveOwnerPriority(ownerKey, oldPriority, priority);
            }
            return;
        }
        ownerPriorities.put(ownerKey, priority);
        pendingOwnersByPriority
                .computeIfAbsent(priority, ignored -> new LinkedHashSet<>())
                .add(ownerKey);
    }

    private void removePending(Collection<String> ownerKeys) {
        synchronized (ownerPriorities) {
            for (String ownerKey : ownerKeys) {
                Integer priority = ownerPriorities.remove(ownerKey);
                ownerFailureCounts.remove(ownerKey);
                isolatedOwners.remove(ownerKey);
                if (priority == null) {
                    continue;
                }
                LinkedHashSet<String> bucket = pendingOwnersByPriority.get(priority);
                if (bucket != null) {
                    bucket.remove(ownerKey);
                    if (bucket.isEmpty()) {
                        pendingOwnersByPriority.remove(priority);
                    }
                }
            }
        }
    }

    private void releaseExpiredIsolations(long nowNanos) {
        var iterator = isolatedOwners.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, IsolatedOwner> entry = iterator.next();
            if (entry.getValue().releaseAtNanos() > nowNanos) {
                continue;
            }
            iterator.remove();
            enqueuePending(entry.getKey(), entry.getValue().priority());
            releasedIsolatedOwners.increment();
        }
    }

    private long remainingIsolationMillis(IsolatedOwner isolatedOwner, long nowNanos) {
        long remainingNanos = isolatedOwner.releaseAtNanos() - nowNanos;
        if (remainingNanos <= 0) {
            return 0L;
        }
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private int highestPendingPriority() {
        if (pendingOwnersByPriority.isEmpty()) {
            return 0;
        }
        return pendingOwnersByPriority.lastKey();
    }

    private void applyBackoff() {
        if (!backoffPolicy.enabled()) {
            return;
        }
        consecutiveFailures++;
        Duration delay = backoffPolicy.delayForFailure(consecutiveFailures);
        backoffUntilNanos = safeAdd(nanoTime.getAsLong(), delay.toNanos());
    }

    private void resetBackoff() {
        consecutiveFailures = 0;
        backoffUntilNanos = 0L;
    }

    private boolean backoffActive(long nowNanos) {
        return backoffPolicy.enabled() && backoffUntilNanos > nowNanos;
    }

    private long backoffRemainingMillis(long nowNanos) {
        if (!backoffActive(nowNanos)) {
            return 0L;
        }
        long remainingNanos = backoffUntilNanos - nowNanos;
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private static long safeAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private static Duration positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static void validateOwnerKey(String ownerKey) {
        Objects.requireNonNull(ownerKey, "ownerKey");
        if (ownerKey.isBlank()) {
            throw new IllegalArgumentException("ownerKey must not be blank");
        }
    }

    private record RepairBatch(Set<String> ownerKeys, Map<String, Integer> priorities) {
        private static RepairBatch empty() {
            return new RepairBatch(Set.of(), Map.of());
        }

        private boolean isEmpty() {
            return ownerKeys.isEmpty();
        }

        private int size() {
            return ownerKeys.size();
        }

        private int priorityOf(String ownerKey) {
            return priorities.getOrDefault(ownerKey, DEFAULT_PRIORITY);
        }

        private String onlyOwnerKey() {
            if (ownerKeys.size() != 1) {
                return null;
            }
            return ownerKeys.iterator().next();
        }
    }

    private record IsolatedOwner(int priority, long releaseAtNanos) {
    }
}
