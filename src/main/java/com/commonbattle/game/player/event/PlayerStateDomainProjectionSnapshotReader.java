package com.commonbattle.game.player.event;

import com.commonbattle.game.player.PlayerStateRepository;

import java.util.Objects;
import java.util.Optional;

/**
 * 从玩家状态仓库投影 PlayerDomainProjectionSnapshot。
 */
public final class PlayerStateDomainProjectionSnapshotReader implements PlayerDomainProjectionSnapshotReader {
    private final PlayerStateRepository repository;

    public PlayerStateDomainProjectionSnapshotReader(PlayerStateRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Optional<PlayerDomainProjectionSnapshot> find(long playerId) {
        return repository.load(playerId).map(PlayerDomainProjectionSnapshot::from);
    }
}
