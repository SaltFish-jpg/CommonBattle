package com.commonbattle.game.snapshot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnapshotReadMetricsTest {
    @Test
    void recordsAllSnapshotReadStatuses() {
        SnapshotReadMetrics metrics = new SnapshotReadMetrics();

        metrics.request();
        metrics.record(SnapshotReadStatus.LOCAL_HIT);
        metrics.request();
        metrics.record(SnapshotReadStatus.LOCAL_STALE);
        metrics.request();
        metrics.record(SnapshotReadStatus.LOCAL_MISS);
        metrics.request();
        metrics.record(SnapshotReadStatus.REFRESHED);
        metrics.request();
        metrics.record(SnapshotReadStatus.REMOTE_STALE);
        metrics.request();
        metrics.record(SnapshotReadStatus.REMOTE_MISS);
        metrics.request();
        metrics.record(SnapshotReadStatus.LOCAL_FALLBACK);

        assertEquals(new SnapshotReadStats(7, 1, 1, 1, 1, 1, 1, 1), metrics.snapshot());
    }

    @Test
    void readStatsCanBeMerged() {
        SnapshotReadStats first = new SnapshotReadStats(2, 1, 0, 0, 1, 0, 0, 0);
        SnapshotReadStats second = new SnapshotReadStats(3, 0, 1, 1, 0, 1, 0, 0);

        assertEquals(new SnapshotReadStats(5, 1, 1, 1, 1, 1, 0, 0), first.plus(second));
    }
}
