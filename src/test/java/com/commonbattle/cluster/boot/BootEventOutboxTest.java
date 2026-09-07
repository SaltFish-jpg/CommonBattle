package com.commonbattle.cluster.boot;

import com.commonbattle.game.event.ReliableVersionedEventPublisher;
import com.commonbattle.game.event.SerializedVersionedEventOutbox;
import com.commonbattle.game.event.VersionedEventOutbox;
import com.commonbattle.game.event.VersionedEventOutboxReplayScheduler;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BootEventOutboxTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void configureCreatesSerializedEventOutbox() {
        BootRuntime runtime = new BootRuntime();
        try {
            VersionedEventOutbox outbox = BootEventOutbox.configure(
                    runtime,
                    ClusterNodeConfig.fromProperties(base()),
                    CLOCK
            );

            assertInstanceOf(SerializedVersionedEventOutbox.class, outbox);
            assertEquals(1, runtime.healthRegistry().outboxes().size());
        } finally {
            runtime.close();
        }
    }

    @Test
    void configureCanCreateJdbcBackedOutboxWithoutSchemaInitialization() {
        BootRuntime runtime = new BootRuntime();
        try {
            Properties properties = base();
            properties.setProperty("cluster.event.outbox.store", "JDBC");
            properties.setProperty("cluster.event.outbox.jdbc.url", "jdbc:fake:outbox");
            properties.setProperty("cluster.event.outbox.jdbc.initialize.schema", "false");

            VersionedEventOutbox outbox = BootEventOutbox.configure(
                    runtime,
                    ClusterNodeConfig.fromProperties(properties),
                    CLOCK
            );

            assertInstanceOf(SerializedVersionedEventOutbox.class, outbox);
            assertEquals(1, runtime.healthRegistry().outboxes().size());
        } finally {
            runtime.close();
        }
    }

    @Test
    void replaySchedulerCanBeDisabledByConfig() {
        BootRuntime runtime = new BootRuntime();
        try {
            Properties properties = base();
            properties.setProperty("cluster.event.outbox.replay.enabled", "false");
            ReliableVersionedEventPublisher publisher = new ReliableVersionedEventPublisher(
                    BootEventOutbox.configure(runtime, ClusterNodeConfig.fromProperties(properties), CLOCK),
                    ignored -> {
                    }
            );

            VersionedEventOutboxReplayScheduler scheduler = BootEventOutbox.configureReplayScheduler(
                    runtime,
                    ClusterNodeConfig.fromProperties(properties),
                    publisher
            );

            assertNull(scheduler);
        } finally {
            runtime.close();
        }
    }

    @Test
    void replaySchedulerRegistersWhenEnabled() {
        BootRuntime runtime = new BootRuntime();
        try {
            Properties properties = base();
            ReliableVersionedEventPublisher publisher = new ReliableVersionedEventPublisher(
                    BootEventOutbox.configure(runtime, ClusterNodeConfig.fromProperties(properties), CLOCK),
                    ignored -> {
                    }
            );

            VersionedEventOutboxReplayScheduler scheduler = BootEventOutbox.configureReplayScheduler(
                    runtime,
                    ClusterNodeConfig.fromProperties(properties),
                    publisher
            );

            assertNotNull(scheduler);
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
        properties.setProperty("cluster.actor.workers", "4");
        return properties;
    }
}
