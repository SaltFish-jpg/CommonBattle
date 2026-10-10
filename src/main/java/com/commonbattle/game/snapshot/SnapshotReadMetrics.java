package com.commonbattle.game.snapshot;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用快照读取指标累加器。
 * 业务在一次读取开始时调用 request，随后把领域状态映射为 SnapshotReadStatus 记录结果。
 */
public final class SnapshotReadMetrics {
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong localHits = new AtomicLong();
    private final AtomicLong localStale = new AtomicLong();
    private final AtomicLong localMisses = new AtomicLong();
    private final AtomicLong refreshes = new AtomicLong();
    private final AtomicLong remoteStale = new AtomicLong();
    private final AtomicLong remoteMisses = new AtomicLong();
    private final AtomicLong localFallbacks = new AtomicLong();

    public void request() {
        requests.incrementAndGet();
    }

    public void record(SnapshotReadStatus status) {
        Objects.requireNonNull(status, "status");
        switch (status) {
            case LOCAL_HIT -> localHits.incrementAndGet();
            case LOCAL_STALE -> localStale.incrementAndGet();
            case LOCAL_MISS -> localMisses.incrementAndGet();
            case REFRESHED -> refreshes.incrementAndGet();
            case REMOTE_STALE -> remoteStale.incrementAndGet();
            case REMOTE_MISS -> remoteMisses.incrementAndGet();
            case LOCAL_FALLBACK -> localFallbacks.incrementAndGet();
        }
    }

    public SnapshotReadStats snapshot() {
        return new SnapshotReadStats(
                requests.get(),
                localHits.get(),
                localStale.get(),
                localMisses.get(),
                refreshes.get(),
                remoteStale.get(),
                remoteMisses.get(),
                localFallbacks.get()
        );
    }
}
