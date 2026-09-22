package com.commonbattle.cluster.boot;

import com.commonbattle.actor.agent.migration.AgentMigrationAcceptResponse;
import com.commonbattle.actor.agent.migration.AgentMigrationTargetReceiptStore;
import com.commonbattle.actor.agent.migration.SerializedAgentMigrationTargetReceiptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootAgentMigrationTargetReceiptsTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void configureCreatesSerializedStoreWhenRetentionEnabledForMemory() {
        BootRuntime runtime = new BootRuntime();
        try {
            Properties properties = base();
            properties.setProperty("cluster.migration.target.receipt.retention.enabled", "true");
            ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

            AgentMigrationTargetReceiptStore store =
                    BootAgentMigrationTargetReceipts.configure(runtime, config, CLOCK);

            assertInstanceOf(SerializedAgentMigrationTargetReceiptStore.class, store);
        } finally {
            runtime.close();
        }
    }

    @Test
    void configureCanUseFileBackedReceiptStore(@TempDir Path directory) {
        Properties properties = base();
        properties.setProperty("cluster.migration.target.receipt.store", "FILE");
        properties.setProperty("cluster.migration.target.receipt.store.dir", directory.toString());
        properties.setProperty("cluster.migration.target.receipt.retention.enabled", "false");
        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);
        BootRuntime firstRuntime = new BootRuntime();
        BootRuntime secondRuntime = new BootRuntime();
        try {
            AgentMigrationTargetReceiptStore first =
                    BootAgentMigrationTargetReceipts.configure(firstRuntime, config, CLOCK);
            first.save("migration-file-1", AgentMigrationAcceptResponse.success());

            AgentMigrationTargetReceiptStore second =
                    BootAgentMigrationTargetReceipts.configure(secondRuntime, config, CLOCK);

            assertEquals(AgentMigrationAcceptResponse.success(), second.find("migration-file-1").orElseThrow());
        } finally {
            firstRuntime.close();
            secondRuntime.close();
        }
    }

    @Test
    void configureCanDisableMemoryRetentionAndUseInMemoryReceiptStore() {
        BootRuntime runtime = new BootRuntime();
        try {
            Properties properties = base();
            properties.setProperty("cluster.migration.target.receipt.retention.enabled", "false");
            ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

            AgentMigrationTargetReceiptStore store =
                    BootAgentMigrationTargetReceipts.configure(runtime, config, CLOCK);
            store.save("migration-memory-1", AgentMigrationAcceptResponse.success());

            assertTrue(store.find("migration-memory-1").orElseThrow().accepted());
        } finally {
            runtime.close();
        }
    }

    private static Properties base() {
        Properties properties = new Properties();
        properties.setProperty("cluster.kind", "GAME");
        properties.setProperty("cluster.region", "r1");
        properties.setProperty("cluster.node", "game-1");
        properties.setProperty("cluster.host", "127.0.0.1");
        properties.setProperty("cluster.port", "9001");
        properties.setProperty("cluster.center.host", "127.0.0.1");
        properties.setProperty("cluster.center.port", "9000");
        properties.setProperty("cluster.migration.target.receipt.store", "MEMORY");
        properties.setProperty("cluster.migration.target.receipt.retention.enabled", "true");
        properties.setProperty("cluster.migration.target.receipt.retention.millis", "86400000");
        properties.setProperty("cluster.migration.target.receipt.retention.scan.interval.millis", "60000");
        return properties;
    }
}
