package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.event.ClusterEventPayloadCodecs;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.event.InMemoryVersionedEventOutboxBytesStore;
import com.commonbattle.game.event.JdbcVersionedEventOutboxBytesStore;
import com.commonbattle.game.event.ReliableVersionedEventPublisher;
import com.commonbattle.game.event.SerializedVersionedEventOutbox;
import com.commonbattle.game.event.VersionedEventOutbox;
import com.commonbattle.game.event.VersionedEventOutboxReplayScheduler;

import java.time.Clock;
import java.util.Objects;

/**
 * 启动期事件 outbox 装配。
 * 负责根据节点配置创建可恢复 outbox，并可选启动 pending 事件后台补发。
 */
final class BootEventOutbox {
    private BootEventOutbox() {
    }

    static VersionedEventOutbox configure(BootRuntime runtime, ClusterNodeConfig config, Clock clock) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        VersionedEventOutbox outbox = new SerializedVersionedEventOutbox(
                bytesStore(config),
                ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults()),
                clock
        );
        runtime.observe("versionedEventOutbox", outbox);
        return outbox;
    }

    static VersionedEventOutboxReplayScheduler configureReplayScheduler(
            BootRuntime runtime,
            ClusterNodeConfig config,
            ReliableVersionedEventPublisher publisher
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(publisher, "publisher");
        if (!config.eventOutboxReplayEnabled()) {
            return null;
        }
        VersionedEventOutboxReplayScheduler scheduler = runtime.add(
                "versionedEventOutboxReplayScheduler",
                new VersionedEventOutboxReplayScheduler(publisher, config.eventOutboxReplayInterval())
        );
        scheduler.start();
        return scheduler;
    }

    private static com.commonbattle.game.event.VersionedEventOutboxBytesStore bytesStore(ClusterNodeConfig config) {
        if (config.eventOutboxStoreKind() == EventOutboxStoreKind.JDBC) {
            JdbcVersionedEventOutboxBytesStore store = new JdbcVersionedEventOutboxBytesStore(
                    new DriverManagerDataSource(
                            config.eventOutboxJdbcDriver(),
                            config.eventOutboxJdbcUrl(),
                            config.eventOutboxJdbcUser(),
                            config.eventOutboxJdbcPassword()
                    ),
                    config.eventOutboxJdbcTable(),
                    config.eventOutboxJdbcSequenceTable()
            );
            if (config.eventOutboxJdbcInitializeSchema()) {
                store.initializeSchema();
            }
            return store;
        }
        return new InMemoryVersionedEventOutboxBytesStore();
    }
}
