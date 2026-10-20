package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.event.ClusterEventJournal;
import com.commonbattle.cluster.event.InMemoryClusterEventJournal;
import com.commonbattle.cluster.event.JdbcClusterEventJournal;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class BootCenterEventJournalTest {
    @Test
    void configuresMemoryJournalByDefault() {
        BootRuntime runtime = new BootRuntime();
        try {
            ClusterEventJournal journal = BootCenterEventJournal.configure(
                    runtime,
                    ClusterNodeConfig.fromProperties(base())
            );

            assertInstanceOf(InMemoryClusterEventJournal.class, journal);
        } finally {
            runtime.close();
        }
    }

    @Test
    void configuresJdbcJournalWithoutEagerConnectionWhenSchemaInitializationDisabled() {
        Properties properties = base();
        properties.setProperty("cluster.event.journal.store", "JDBC");
        properties.setProperty("cluster.event.journal.jdbc.url", "jdbc:fake:center-events");
        properties.setProperty("cluster.event.journal.jdbc.initialize.schema", "false");
        BootRuntime runtime = new BootRuntime();
        try {
            ClusterEventJournal journal = BootCenterEventJournal.configure(
                    runtime,
                    ClusterNodeConfig.fromProperties(properties)
            );

            assertInstanceOf(JdbcClusterEventJournal.class, journal);
        } finally {
            runtime.close();
        }
    }

    private static Properties base() {
        Properties properties = new Properties();
        properties.setProperty("cluster.kind", "CENTER");
        properties.setProperty("cluster.region", "r1");
        properties.setProperty("cluster.node", "center-1");
        properties.setProperty("cluster.host", "127.0.0.1");
        properties.setProperty("cluster.port", "9000");
        properties.setProperty("cluster.center.host", "127.0.0.1");
        properties.setProperty("cluster.center.port", "9000");
        properties.setProperty("cluster.actor.workers", "2");
        return properties;
    }
}
