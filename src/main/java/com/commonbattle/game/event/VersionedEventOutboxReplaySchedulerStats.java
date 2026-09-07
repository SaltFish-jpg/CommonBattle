package com.commonbattle.game.event;

/**
 * 版本事件 outbox 补发调度器统计快照。
 */
public record VersionedEventOutboxReplaySchedulerStats(long runs, long failedRuns) {
    public static VersionedEventOutboxReplaySchedulerStats empty() {
        return new VersionedEventOutboxReplaySchedulerStats(0, 0);
    }
}
