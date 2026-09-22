package com.commonbattle.cluster.boot;

import com.commonbattle.actor.agent.migration.AgentMigrationTargetReceiptRetentionScheduler;
import com.commonbattle.actor.agent.migration.AgentMigrationTargetReceiptRetentionService;
import com.commonbattle.actor.agent.migration.AgentMigrationTargetReceiptStore;
import com.commonbattle.actor.agent.migration.InMemoryAgentMigrationTargetReceiptStore;
import com.commonbattle.actor.agent.migration.ProtoAgentMigrationTargetReceiptSerializer;
import com.commonbattle.actor.agent.migration.SerializedAgentMigrationTargetReceiptStore;
import com.commonbattle.persistence.FileAtomicBytesStore;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;

import java.time.Clock;
import java.util.Objects;

/**
 * 启动期目标迁入回执组件装配。
 */
final class BootAgentMigrationTargetReceipts {
    private BootAgentMigrationTargetReceipts() {
    }

    static AgentMigrationTargetReceiptStore configure(BootRuntime runtime, ClusterNodeConfig config, Clock clock) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        if (config.migrationTargetReceiptStoreKind() == AgentMigrationTargetReceiptStoreKind.FILE) {
            SerializedAgentMigrationTargetReceiptStore store = new SerializedAgentMigrationTargetReceiptStore(
                    new FileAtomicBytesStore(config.migrationTargetReceiptStoreDirectory()),
                    new ProtoAgentMigrationTargetReceiptSerializer(),
                    clock
            );
            configureRetention(runtime, config, clock, store);
            return store;
        }
        if (config.migrationTargetReceiptRetentionEnabled()) {
            SerializedAgentMigrationTargetReceiptStore store = new SerializedAgentMigrationTargetReceiptStore(
                    new InMemoryAtomicBytesStore(),
                    new ProtoAgentMigrationTargetReceiptSerializer(),
                    clock
            );
            configureRetention(runtime, config, clock, store);
            return store;
        }
        return new InMemoryAgentMigrationTargetReceiptStore();
    }

    private static void configureRetention(
            BootRuntime runtime,
            ClusterNodeConfig config,
            Clock clock,
            SerializedAgentMigrationTargetReceiptStore store
    ) {
        if (!config.migrationTargetReceiptRetentionEnabled()) {
            return;
        }
        AgentMigrationTargetReceiptRetentionService retention =
                new AgentMigrationTargetReceiptRetentionService(
                        store,
                        clock,
                        config.migrationTargetReceiptRetention()
                );
        AgentMigrationTargetReceiptRetentionScheduler scheduler = runtime.add(
                "agentMigrationTargetReceiptRetentionScheduler",
                new AgentMigrationTargetReceiptRetentionScheduler(
                        retention,
                        config.migrationTargetReceiptRetentionScanInterval()
                )
        );
        scheduler.start();
    }
}
