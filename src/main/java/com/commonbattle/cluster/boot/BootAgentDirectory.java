package com.commonbattle.cluster.boot;

import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.SerializedAgentDirectory;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import com.commonbattle.persistence.JdbcAtomicBytesStore;

import java.time.Clock;
import java.util.Objects;

/**
 * Center 全局 Agent 目录的启动装配入口。
 */
final class BootAgentDirectory {
    private BootAgentDirectory() {
    }

    static AgentDirectory configure(ClusterNodeConfig config, Clock clock) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        return switch (config.agentDirectoryStoreKind()) {
            case MEMORY -> new SerializedAgentDirectory(new InMemoryAtomicBytesStore(), clock);
            case JDBC -> new SerializedAgentDirectory(jdbcStore(config), clock);
        };
    }

    private static JdbcAtomicBytesStore jdbcStore(ClusterNodeConfig config) {
        JdbcAtomicBytesStore store = new JdbcAtomicBytesStore(
                new DriverManagerDataSource(
                        config.agentDirectoryJdbcDriver(),
                        config.agentDirectoryJdbcUrl(),
                        config.agentDirectoryJdbcUser(),
                        config.agentDirectoryJdbcPassword()
                ),
                config.agentDirectoryJdbcTable()
        );
        if (config.agentDirectoryJdbcInitializeSchema()) {
            store.initializeSchema();
        }
        return store;
    }
}
