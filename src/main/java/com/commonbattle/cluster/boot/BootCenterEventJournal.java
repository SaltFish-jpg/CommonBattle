package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.event.ClusterEventJournal;
import com.commonbattle.cluster.event.ClusterEventPayloadCodecs;
import com.commonbattle.cluster.event.InMemoryClusterEventJournal;
import com.commonbattle.cluster.event.JdbcClusterEventJournal;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;

import java.util.Objects;

/**
 * 中心服 durable event journal 启动装配。
 */
final class BootCenterEventJournal {
    private BootCenterEventJournal() {
    }

    static ClusterEventJournal configure(BootRuntime runtime, ClusterNodeConfig config) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(config, "config");
        ClusterEventJournal journal;
        if (config.centerEventJournalStoreKind() == CenterEventJournalStoreKind.JDBC) {
            JdbcClusterEventJournal jdbc = new JdbcClusterEventJournal(
                    new DriverManagerDataSource(
                            config.centerEventJournalJdbcDriver(),
                            config.centerEventJournalJdbcUrl(),
                            config.centerEventJournalJdbcUser(),
                            config.centerEventJournalJdbcPassword()
                    ),
                    ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults()),
                    config.region(),
                    config.centerEventJournalJdbcTable(),
                    config.centerEventJournalJdbcSequenceTable(),
                    config.centerEventJournalJdbcCursorTable()
            );
            if (config.centerEventJournalJdbcInitializeSchema()) {
                jdbc.initializeSchema();
            }
            journal = jdbc;
        } else {
            journal = new InMemoryClusterEventJournal();
        }
        runtime.observe("clusterEventJournal", journal);
        return journal;
    }
}
