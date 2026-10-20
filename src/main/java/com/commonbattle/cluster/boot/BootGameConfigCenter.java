package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.config.GameConfigCenterRecovery;
import com.commonbattle.game.config.GameConfigCenterStateStore;
import com.commonbattle.game.config.GameConfigRegistry;
import com.commonbattle.game.config.GameConfigSnapshot;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.InMemoryGameConfigRegistry;
import com.commonbattle.game.config.SerializedGameConfigCenterStateStore;
import com.commonbattle.persistence.AtomicBytesStore;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import com.commonbattle.persistence.JdbcAtomicBytesStore;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Center 权威游戏配置注册表与持久化状态的启动装配入口。
 */
final class BootGameConfigCenter {
    private BootGameConfigCenter() {
    }

    static Runtime configure(
            ClusterNodeConfig config,
            PayloadCodecRegistry payloadCodecs,
            Clock clock
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(payloadCodecs, "payloadCodecs");
        Objects.requireNonNull(clock, "clock");
        AtomicBytesStore bytes = switch (config.gameConfigCenterStoreKind()) {
            case MEMORY -> new InMemoryAtomicBytesStore();
            case JDBC -> jdbcStore(config);
        };
        GameConfigCenterStateStore stateStore = new SerializedGameConfigCenterStateStore(bytes, payloadCodecs);
        GameConfigRegistry registry = new InMemoryGameConfigRegistry(new GameConfigValidator(), clock);
        Optional<GameConfigSnapshot> recovered = GameConfigCenterRecovery.restore(registry, stateStore);
        return new Runtime(registry, stateStore, recovered);
    }

    private static JdbcAtomicBytesStore jdbcStore(ClusterNodeConfig config) {
        JdbcAtomicBytesStore store = new JdbcAtomicBytesStore(
                new DriverManagerDataSource(
                        config.gameConfigCenterJdbcDriver(),
                        config.gameConfigCenterJdbcUrl(),
                        config.gameConfigCenterJdbcUser(),
                        config.gameConfigCenterJdbcPassword()
                ),
                config.gameConfigCenterJdbcTable()
        );
        if (config.gameConfigCenterJdbcInitializeSchema()) {
            store.initializeSchema();
        }
        return store;
    }

    record Runtime(
            GameConfigRegistry registry,
            GameConfigCenterStateStore stateStore,
            Optional<GameConfigSnapshot> recovered
    ) {
        Runtime {
            Objects.requireNonNull(registry, "registry");
            Objects.requireNonNull(stateStore, "stateStore");
            Objects.requireNonNull(recovered, "recovered");
        }
    }
}
