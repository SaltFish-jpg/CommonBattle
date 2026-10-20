package com.commonbattle.game.player;

/**
 * 玩家事务事件后台补交统计。
 */
public record PlayerMutationReplaySchedulerStats(
        long runs,
        long submittedPlayers,
        long failedRuns
) {
    public static PlayerMutationReplaySchedulerStats empty() {
        return new PlayerMutationReplaySchedulerStats(0, 0, 0);
    }

    public PlayerMutationReplaySchedulerStats plus(PlayerMutationReplaySchedulerStats other) {
        return new PlayerMutationReplaySchedulerStats(
                runs + other.runs,
                submittedPlayers + other.submittedPlayers,
                failedRuns + other.failedRuns
        );
    }
}
