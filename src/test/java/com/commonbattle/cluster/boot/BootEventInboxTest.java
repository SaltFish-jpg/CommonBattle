package com.commonbattle.cluster.boot;

import com.commonbattle.game.event.EventInboxRetryPolicy;
import com.commonbattle.game.event.SerializedVersionedEventInbox;
import com.commonbattle.game.event.VersionedEventInbox;
import com.commonbattle.game.event.VersionedEventInboxReplayScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class BootEventInboxTest {
    @Test
    void configureCreatesSerializedMemoryInbox() {
        BootRuntime runtime = new BootRuntime();
        try {
            VersionedEventInbox inbox = BootEventInbox.configure(runtime, ClusterNodeConfig.fromProperties(base()));

            assertInstanceOf(SerializedVersionedEventInbox.class, inbox);
        } finally {
            runtime.close();
        }
    }

    @Test
    void configureCanCreateJdbcInboxWithoutInitializingSchema() {
        BootRuntime runtime = new BootRuntime();
        try {
            Properties properties = base();
            properties.setProperty("cluster.event.inbox.store", "JDBC");
            properties.setProperty("cluster.event.inbox.jdbc.url", "jdbc:fake:inbox");
            properties.setProperty("cluster.event.inbox.jdbc.initialize.schema", "false");

            VersionedEventInbox inbox = BootEventInbox.configure(
                    runtime,
                    ClusterNodeConfig.fromProperties(properties)
            );

            assertInstanceOf(SerializedVersionedEventInbox.class, inbox);
        } finally {
            runtime.close();
        }
    }

    @Test
    void retryPolicyAndDisabledSchedulerComeFromConfig() {
        BootRuntime runtime = new BootRuntime();
        try {
            Properties properties = base();
            properties.setProperty("cluster.event.inbox.retry.max.attempts", "4");
            properties.setProperty("cluster.event.inbox.retry.initial.backoff.millis", "25");
            properties.setProperty("cluster.event.inbox.retry.max.backoff.millis", "500");
            properties.setProperty("cluster.event.inbox.replay.batch.size", "32");
            properties.setProperty("cluster.event.inbox.replay.enabled", "false");
            ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

            EventInboxRetryPolicy policy = BootEventInbox.retryPolicy(config);
            VersionedEventInboxReplayScheduler scheduler =
                    BootEventInbox.startReplayScheduler(runtime, config, List.of());

            assertEquals(4, policy.maxAttempts());
            assertEquals(Duration.ofMillis(25), policy.initialBackoff());
            assertEquals(Duration.ofMillis(500), policy.maxBackoff());
            assertEquals(32, policy.replayBatchSize());
            assertNull(scheduler);
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
        properties.setProperty("cluster.actor.workers", "4");
        properties.setProperty("scene.mode", "MULTI_SMALL");
        properties.setProperty("scene.id", "scene-small");
        properties.setProperty("scene.capacity", "100");
        properties.setProperty("scene.shards", "1");
        properties.setProperty("scene.shard.chunk.size", "16");
        return properties;
    }
}
