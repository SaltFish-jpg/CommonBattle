package com.commonbattle.cluster.boot;

import com.commonbattle.actor.agent.migration.AgentMigrationRecoveryScheduler;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoveryService;
import com.commonbattle.actor.agent.migration.AgentMigrationResultCallback;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskRetentionScheduler;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskRetentionService;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStore;
import com.commonbattle.actor.agent.migration.FileAgentMigrationTaskBytesStore;
import com.commonbattle.actor.agent.migration.InMemoryAgentMigrationTaskBytesStore;
import com.commonbattle.actor.agent.migration.JdbcAgentMigrationTaskBytesStore;
import com.commonbattle.actor.agent.migration.ProtoAgentMigrationTaskSerializer;
import com.commonbattle.actor.agent.migration.SerializedAgentMigrationTaskStore;

import java.time.Clock;
import java.util.Objects;

/**
 * 启动期 Agent 迁移任务组件装配。
 * 当前默认使用内存字节存储作为示例实现，生产环境可在此替换为 Redis 或 DB 字节存储。
 */
final class BootAgentMigrationTasks {
    private BootAgentMigrationTasks() {
    }

    static AgentMigrationTaskStore configure(BootRuntime runtime, ClusterNodeConfig config, Clock clock) {
        return configureRuntime(runtime, config, clock).taskStore();
    }

    static BootAgentMigrationRuntime configureRuntime(BootRuntime runtime, ClusterNodeConfig config, Clock clock) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        AgentMigrationTaskStore taskStore = new SerializedAgentMigrationTaskStore(
                bytesStore(config),
                new ProtoAgentMigrationTaskSerializer()
        );
        runtime.observe("agentMigrationTaskStore", taskStore);
        if (!config.migrationTaskRetentionEnabled()) {
            return runtime(taskStore, config);
        }
        AgentMigrationTaskRetentionService retention = new AgentMigrationTaskRetentionService(
                taskStore,
                clock,
                config.migrationTaskRetention()
        );
        runtime.observe("agentMigrationTaskRetention", retention);
        AgentMigrationTaskRetentionScheduler scheduler = runtime.add(
                "agentMigrationTaskRetentionScheduler",
                new AgentMigrationTaskRetentionScheduler(retention, config.migrationTaskRetentionScanInterval())
        );
        scheduler.start();
        return runtime(taskStore, config);
    }

    static void configureRecovery(
            BootRuntime runtime,
            ClusterNodeConfig config,
            AgentMigrationRecoveryService recoveryService,
            AgentMigrationResultCallback callback
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(recoveryService, "recoveryService");
        Objects.requireNonNull(callback, "callback");
        runtime.observe("agentMigrationRecovery", recoveryService);
        if (!config.migrationRecoveryEnabled()) {
            return;
        }
        AgentMigrationRecoveryScheduler scheduler = runtime.add(
                "agentMigrationRecoveryScheduler",
                new AgentMigrationRecoveryScheduler(recoveryService, callback, config.migrationRecoveryScanInterval())
        );
        scheduler.start();
    }

    private static BootAgentMigrationRuntime runtime(AgentMigrationTaskStore taskStore, ClusterNodeConfig config) {
        return new BootAgentMigrationRuntime(
                taskStore,
                config.migrationPolicy(),
                config.migrationRecoveryEnabled(),
                config.migrationRecoveryScanInterval(),
                config.migrationRecoveryLeaseTtl()
        );
    }

    private static com.commonbattle.actor.agent.migration.AgentMigrationTaskBytesStore bytesStore(
            ClusterNodeConfig config
    ) {
        if (config.migrationTaskStoreKind() == AgentMigrationTaskStoreKind.FILE) {
            return new FileAgentMigrationTaskBytesStore(config.migrationTaskStoreDirectory());
        }
        if (config.migrationTaskStoreKind() == AgentMigrationTaskStoreKind.JDBC) {
            JdbcAgentMigrationTaskBytesStore store = new JdbcAgentMigrationTaskBytesStore(
                    new DriverManagerDataSource(
                            config.migrationTaskJdbcDriver(),
                            config.migrationTaskJdbcUrl(),
                            config.migrationTaskJdbcUser(),
                            config.migrationTaskJdbcPassword()
                    ),
                    config.migrationTaskJdbcTable()
            );
            if (config.migrationTaskJdbcInitializeSchema()) {
                store.initializeSchema();
            }
            return store;
        }
        return new InMemoryAgentMigrationTaskBytesStore();
    }
}
