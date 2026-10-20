package com.commonbattle.game.player;

import java.util.Objects;
import java.util.Optional;

/**
 * 聚合玩家业务状态仓库与完成响应检查点仓库的启动期依赖。
 * 玩家状态接口保持不变，运行时可从该装饰器取得与其同等级的响应存储实现。
 */
public final class PlayerCheckpointRepository implements PlayerStateRepository, PlayerMutationRepositoryView {
    private final PlayerStateRepository states;
    private final PlayerBusinessResponseStore responses;

    public PlayerCheckpointRepository(
            PlayerStateRepository states,
            PlayerBusinessResponseStore responses
    ) {
        this.states = Objects.requireNonNull(states, "states");
        this.responses = Objects.requireNonNull(responses, "responses");
    }

    @Override
    public Optional<PlayerStateSnapshot> load(Long key) {
        return states.load(key);
    }

    @Override
    public void save(Long key, PlayerStateSnapshot snapshot) {
        states.save(key, snapshot);
    }

    public PlayerBusinessResponseStore responses() {
        return responses;
    }

    public java.util.Optional<PlayerMutationRepository> mutations() {
        return states instanceof PlayerMutationRepository mutations
                ? java.util.Optional.of(mutations)
                : java.util.Optional.empty();
    }

    @Override
    public PlayerMutationRepositoryStats mutationRepositoryStats() {
        return states instanceof PlayerMutationRepositoryView view
                ? view.mutationRepositoryStats()
                : PlayerMutationRepositoryStats.empty();
    }

    @Override
    public java.util.List<PlayerMutationPendingRecord> pendingMutationRecords(int limit) {
        return states instanceof PlayerMutationRepositoryView view
                ? view.pendingMutationRecords(limit)
                : java.util.List.of();
    }
}
