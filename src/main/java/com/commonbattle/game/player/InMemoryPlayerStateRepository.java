package com.commonbattle.game.player;

import com.commonbattle.actor.persistence.InMemoryAgentStateRepository;

import java.util.Optional;

/**
 * 内存玩家状态仓库，用于测试和本地样例。
 */
public final class InMemoryPlayerStateRepository implements PlayerStateRepository {
    private final InMemoryAgentStateRepository<Long, PlayerStateSnapshot> delegate = new InMemoryAgentStateRepository<>();

    @Override
    public Optional<PlayerStateSnapshot> load(Long key) {
        return delegate.load(key);
    }

    @Override
    public void save(Long key, PlayerStateSnapshot snapshot) {
        delegate.save(key, snapshot);
    }
}
