package com.commonbattle.game.player;

/**
 * 玩家状态与事件原子提交统计。
 */
public record PlayerMutationRepositoryStats(
        int records,
        int pendingEvents,
        long commits,
        long commitFailures,
        long committedEvents,
        long enqueuedEvents,
        long casRetries,
        long projectionFailures,
        long statsFailures
) {
    public static PlayerMutationRepositoryStats empty() {
        return new PlayerMutationRepositoryStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public PlayerMutationRepositoryStats plus(PlayerMutationRepositoryStats other) {
        return new PlayerMutationRepositoryStats(
                records + other.records,
                pendingEvents + other.pendingEvents,
                commits + other.commits,
                commitFailures + other.commitFailures,
                committedEvents + other.committedEvents,
                enqueuedEvents + other.enqueuedEvents,
                casRetries + other.casRetries,
                projectionFailures + other.projectionFailures,
                statsFailures + other.statsFailures
        );
    }
}
