package com.commonbattle.game.event;

/**
 * durable inbox 补投调度器统计。
 */
public record VersionedEventInboxReplaySchedulerStats(
        long runs,
        long subscriberRuns,
        long failedSubscriberRuns,
        int subscribers
) {
}
