package com.commonbattle.game.player;

import java.time.Clock;
import java.util.Objects;

/**
 * 玩家 Agent 恢复服务。
 * 启动或迁移接管时先读快照仓库，读不到才创建新玩家状态。
 */
public final class PlayerAgentRecoveryService {
    private final PlayerStateRepository repository;
    private final Clock clock;

    public PlayerAgentRecoveryService(PlayerStateRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PlayerAgentRecovery recover(long playerId) {
        return repository.load(playerId)
                .map(PlayerAgentRecovery::restore)
                .orElseGet(() -> PlayerAgentRecovery.createNew(playerId, clock.instant()));
    }
}
