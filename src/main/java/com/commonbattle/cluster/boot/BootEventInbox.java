package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.event.ClusterEventPayloadCodecs;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.event.ActorMailboxEventSubscriber;
import com.commonbattle.game.event.EventInboxRetryPolicy;
import com.commonbattle.game.event.InMemoryVersionedEventInboxBytesStore;
import com.commonbattle.game.event.JdbcVersionedEventInboxBytesStore;
import com.commonbattle.game.event.SerializedVersionedEventInbox;
import com.commonbattle.game.event.VersionedEventInbox;
import com.commonbattle.game.event.VersionedEventInboxBytesStore;
import com.commonbattle.game.event.VersionedEventInboxReplayScheduler;

import java.util.Collection;
import java.util.Objects;

/**
 * Scene、Chat 等事件消费节点的 durable inbox 启动装配。
 */
final class BootEventInbox {
    private BootEventInbox() {
    }

    static VersionedEventInbox configure(BootRuntime runtime, ClusterNodeConfig config) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(config, "config");
        VersionedEventInbox inbox = new SerializedVersionedEventInbox(
                bytesStore(config),
                ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults()),
                config.kind().name() + ":" + config.region() + ":" + config.node()
        );
        runtime.observe("versionedEventInbox", inbox);
        return inbox;
    }

    static EventInboxRetryPolicy retryPolicy(ClusterNodeConfig config) {
        return new EventInboxRetryPolicy(
                config.eventInboxRetryMaxAttempts(),
                config.eventInboxRetryInitialBackoff(),
                config.eventInboxRetryMaxBackoff(),
                config.eventInboxReplayBatchSize()
        );
    }

    static VersionedEventInboxReplayScheduler startReplayScheduler(
            BootRuntime runtime,
            ClusterNodeConfig config,
            Collection<ActorMailboxEventSubscriber> subscribers
    ) {
        if (!config.eventInboxReplayEnabled()) {
            return null;
        }
        VersionedEventInboxReplayScheduler scheduler = runtime.add(
                "versionedEventInboxReplayScheduler",
                new VersionedEventInboxReplayScheduler(config.eventInboxReplayInterval())
        );
        subscribers.forEach(scheduler::register);
        scheduler.start();
        return scheduler;
    }

    private static VersionedEventInboxBytesStore bytesStore(ClusterNodeConfig config) {
        if (config.eventInboxStoreKind() == EventInboxStoreKind.JDBC) {
            JdbcVersionedEventInboxBytesStore store = new JdbcVersionedEventInboxBytesStore(
                    new DriverManagerDataSource(
                            config.eventInboxJdbcDriver(),
                            config.eventInboxJdbcUrl(),
                            config.eventInboxJdbcUser(),
                            config.eventInboxJdbcPassword()
                    ),
                    config.eventInboxJdbcTable()
            );
            if (config.eventInboxJdbcInitializeSchema()) {
                store.initializeSchema();
            }
            return store;
        }
        return new InMemoryVersionedEventInboxBytesStore();
    }
}
