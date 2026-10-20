package com.commonbattle.game.player;

import java.util.Objects;
import java.util.Optional;

/**
 * 将完成响应存储接口适配到玩家 mutation 原子记录。
 */
public final class MutationPlayerBusinessResponseStore implements PlayerBusinessResponseStore {
    private final PlayerMutationRepository mutations;

    public MutationPlayerBusinessResponseStore(PlayerMutationRepository mutations) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
    }

    @Override
    public Optional<PlayerBusinessResponseSnapshot> load(long playerId) {
        return mutations.loadResponses(playerId);
    }

    @Override
    public void save(PlayerBusinessResponseSnapshot snapshot) {
        mutations.saveResponses(snapshot);
    }
}
