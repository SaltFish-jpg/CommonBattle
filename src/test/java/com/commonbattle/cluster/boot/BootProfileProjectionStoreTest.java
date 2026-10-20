package com.commonbattle.cluster.boot;

import com.commonbattle.game.profile.ProfileProjectionStore;
import com.commonbattle.game.profile.SerializedProfileProjectionStore;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class BootProfileProjectionStoreTest {
    @Test
    void configuresMemoryProjectionByDefault() {
        BootRuntime runtime = new BootRuntime();
        try {
            ProfileProjectionStore store = BootProfileProjectionStore.configure(
                    runtime,
                    ClusterNodeConfig.fromProperties(base())
            );

            assertInstanceOf(SerializedProfileProjectionStore.class, store);
        } finally {
            runtime.close();
        }
    }

    @Test
    void configuresJdbcProjectionWithoutEagerConnectionWhenSchemaInitializationDisabled() {
        Properties properties = base();
        properties.setProperty("cluster.profile.projection.store", "JDBC");
        properties.setProperty("cluster.profile.projection.jdbc.url", "jdbc:fake:profile-projection");
        properties.setProperty("cluster.profile.projection.jdbc.initialize.schema", "false");
        BootRuntime runtime = new BootRuntime();
        try {
            ProfileProjectionStore store = BootProfileProjectionStore.configure(
                    runtime,
                    ClusterNodeConfig.fromProperties(properties)
            );

            assertInstanceOf(SerializedProfileProjectionStore.class, store);
        } finally {
            runtime.close();
        }
    }

    private static Properties base() {
        Properties properties = new Properties();
        properties.setProperty("cluster.kind", "SCENE");
        properties.setProperty("cluster.region", "r1");
        properties.setProperty("cluster.node", "scene-1");
        properties.setProperty("cluster.host", "127.0.0.1");
        properties.setProperty("cluster.port", "9002");
        properties.setProperty("cluster.center.host", "127.0.0.1");
        properties.setProperty("cluster.center.port", "9000");
        properties.setProperty("cluster.actor.workers", "2");
        return properties;
    }
}
