package com.commonbattle.game.player;

/**
 * 玩家事务提交仓库的只读运行指标视图。
 */
public interface PlayerMutationRepositoryView {
    PlayerMutationRepositoryStats mutationRepositoryStats();

    default java.util.List<PlayerMutationPendingRecord> pendingMutationRecords(int limit) {
        return java.util.List.of();
    }
}
