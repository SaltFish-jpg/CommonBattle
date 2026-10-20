package com.commonbattle.cluster.boot;

import com.commonbattle.game.profile.InMemoryProfileProjectionBytesStore;
import com.commonbattle.game.profile.JdbcProfileProjectionBytesStore;
import com.commonbattle.game.profile.ProfileProjectionBytesStore;
import com.commonbattle.game.profile.ProfileProjectionStore;
import com.commonbattle.game.profile.SerializedProfileProjectionStore;

import java.util.Objects;

/**
 * Scene、Chat 本地 Profile 投影启动装配。
 */
final class BootProfileProjectionStore {
    private BootProfileProjectionStore() {
    }

    static ProfileProjectionStore configure(BootRuntime runtime, ClusterNodeConfig config) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(config, "config");
        ProfileProjectionBytesStore bytesStore;
        if (config.profileProjectionStoreKind() == ProfileProjectionStoreKind.JDBC) {
            JdbcProfileProjectionBytesStore jdbc = new JdbcProfileProjectionBytesStore(
                    new DriverManagerDataSource(
                            config.profileProjectionJdbcDriver(),
                            config.profileProjectionJdbcUrl(),
                            config.profileProjectionJdbcUser(),
                            config.profileProjectionJdbcPassword()
                    ),
                    config.profileProjectionJdbcTable()
            );
            if (config.profileProjectionJdbcInitializeSchema()) {
                jdbc.initializeSchema();
            }
            bytesStore = jdbc;
        } else {
            bytesStore = new InMemoryProfileProjectionBytesStore();
        }
        ProfileProjectionStore store = new SerializedProfileProjectionStore(
                bytesStore,
                config.kind().name() + ":" + config.region() + ":" + config.node()
        );
        runtime.observe("profileProjectionStore", store);
        return store;
    }
}
