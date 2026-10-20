package com.commonbattle.cluster.boot;

import com.commonbattle.game.player.InMemoryPlayerStateRepository;
import com.commonbattle.game.player.InMemoryPlayerBusinessResponseStore;
import com.commonbattle.game.player.PlayerBusinessResponseSnapshotCodec;
import com.commonbattle.game.player.PlayerBusinessResponseStore;
import com.commonbattle.game.player.MutationPlayerBusinessResponseStore;
import com.commonbattle.game.player.PlayerCheckpointRepository;
import com.commonbattle.game.player.PlayerStateRepository;
import com.commonbattle.game.player.ProtostuffPlayerStateSnapshotSerializer;
import com.commonbattle.game.player.SerializedPlayerBusinessResponseStore;
import com.commonbattle.game.player.SerializedPlayerStateRepository;
import com.commonbattle.game.player.SerializedPlayerMutationRepository;
import com.commonbattle.persistence.FileAtomicBytesStore;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import com.commonbattle.persistence.AtomicBytesStore;
import com.commonbattle.persistence.JdbcAtomicBytesStore;

/**
 * 玩家状态仓库启动工厂。
 */
final class BootPlayerStateRepository {
    private BootPlayerStateRepository() {
    }

    static PlayerStateRepository configure(BootRuntime runtime, ClusterNodeConfig config) {
        ProtostuffPlayerStateSnapshotSerializer serializer = new ProtostuffPlayerStateSnapshotSerializer();
        AtomicBytesStore bytes = bytesStore(config);
        PlayerStateRepository legacyProjection = config.playerStateStoreKind() == PlayerStateStoreKind.MEMORY
                ? new InMemoryPlayerStateRepository()
                : new SerializedPlayerStateRepository(bytes, serializer);
        PlayerBusinessResponseSnapshotCodec responseCodec =
                new PlayerBusinessResponseSnapshotCodec(BootPayloadCodecs.gameServer());
        PlayerBusinessResponseStore legacyResponses = switch (config.playerStateStoreKind()) {
            case FILE -> new SerializedPlayerBusinessResponseStore(
                    new FileAtomicBytesStore(config.playerStateStoreDirectory().resolve("business-responses")),
                    responseCodec
            );
            case JDBC -> new SerializedPlayerBusinessResponseStore(
                    bytes,
                    responseCodec
            );
            case MEMORY -> new InMemoryPlayerBusinessResponseStore();
        };
        SerializedPlayerMutationRepository repository = new SerializedPlayerMutationRepository(
                bytes, legacyProjection, serializer, legacyResponses, responseCodec
        );
        PlayerCheckpointRepository checkpoints = new PlayerCheckpointRepository(
                repository,
                new MutationPlayerBusinessResponseStore(repository)
        );
        runtime.observe("playerStateRepository", checkpoints);
        return checkpoints;
    }

    private static AtomicBytesStore bytesStore(ClusterNodeConfig config) {
        return switch (config.playerStateStoreKind()) {
            case MEMORY -> new InMemoryAtomicBytesStore();
            case FILE -> new FileAtomicBytesStore(config.playerStateStoreDirectory());
            case JDBC -> jdbcBytesStore(config);
        };
    }

    private static JdbcAtomicBytesStore jdbcBytesStore(ClusterNodeConfig config) {
        JdbcAtomicBytesStore store = new JdbcAtomicBytesStore(
                new DriverManagerDataSource(
                        config.playerStateJdbcDriver(),
                        config.playerStateJdbcUrl(),
                        config.playerStateJdbcUser(),
                        config.playerStateJdbcPassword()
                ),
                config.playerStateJdbcTable()
        );
        if (config.playerStateJdbcInitializeSchema()) {
            store.initializeSchema();
        }
        return store;
    }
}
