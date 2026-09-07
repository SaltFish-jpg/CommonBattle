package com.commonbattle.cluster.boot;

import com.commonbattle.game.player.InMemoryPlayerStateRepository;
import com.commonbattle.game.player.PlayerStateRepository;
import com.commonbattle.game.player.ProtostuffPlayerStateSnapshotSerializer;
import com.commonbattle.game.player.SerializedPlayerStateRepository;
import com.commonbattle.persistence.FileAtomicBytesStore;

/**
 * 玩家状态仓库启动工厂。
 */
final class BootPlayerStateRepository {
    private BootPlayerStateRepository() {
    }

    static PlayerStateRepository configure(BootRuntime runtime, ClusterNodeConfig config) {
        PlayerStateRepository repository = switch (config.playerStateStoreKind()) {
            case FILE -> new SerializedPlayerStateRepository(
                    new FileAtomicBytesStore(config.playerStateStoreDirectory()),
                    new ProtostuffPlayerStateSnapshotSerializer()
            );
            case MEMORY -> new InMemoryPlayerStateRepository();
        };
        runtime.observe("playerStateRepository", repository);
        return repository;
    }
}
