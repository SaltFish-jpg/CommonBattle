package com.commonbattle.cluster.boot;

import com.commonbattle.game.chat.ExecutorChatStateStore;
import com.commonbattle.game.chat.ProtoChatStateSerializer;
import com.commonbattle.game.chat.SerializedChatStateStore;
import com.commonbattle.persistence.AtomicBytesStore;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import com.commonbattle.persistence.JdbcAtomicBytesStore;

import java.util.Objects;

/**
 * Chat Agent 持久化启动装配。
 */
final class BootChatStateStore {
    private BootChatStateStore() {
    }

    static ExecutorChatStateStore configure(BootRuntime runtime, ClusterNodeConfig config) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(config, "config");
        AtomicBytesStore bytesStore;
        if (config.chatStateStoreKind() == ChatStateStoreKind.JDBC) {
            JdbcAtomicBytesStore jdbc = new JdbcAtomicBytesStore(
                    new DriverManagerDataSource(
                            config.chatStateJdbcDriver(),
                            config.chatStateJdbcUrl(),
                            config.chatStateJdbcUser(),
                            config.chatStateJdbcPassword()
                    ),
                    config.chatStateJdbcTable()
            );
            if (config.chatStateJdbcInitializeSchema()) {
                jdbc.initializeSchema();
            }
            bytesStore = jdbc;
        } else {
            bytesStore = new InMemoryAtomicBytesStore();
        }
        SerializedChatStateStore durable = new SerializedChatStateStore(
                bytesStore, new ProtoChatStateSerializer()
        );
        runtime.observe("chatStateStore", durable);
        return runtime.add("asyncChatStateStore", new ExecutorChatStateStore(
                durable,
                config.chatStateIoWorkers(),
                config.chatStateIoQueueCapacity(),
                "chat-state-io-" + config.node()
        ));
    }
}
