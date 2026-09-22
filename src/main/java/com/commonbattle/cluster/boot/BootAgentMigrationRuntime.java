package com.commonbattle.cluster.boot;

import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.migration.AgentMigrationClient;
import com.commonbattle.actor.agent.migration.AgentMigrationCoordinator;
import com.commonbattle.actor.agent.migration.AgentMigrationPolicy;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoveryService;
import com.commonbattle.actor.agent.migration.AgentMigrationSourceHook;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskIdGenerator;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStore;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 启动期 Agent 迁移运行时组件。
 * Game/Scene 装配迁移协调器和恢复器时，从这里取得同一份任务存储、恢复租约和重试策略。
 */
record BootAgentMigrationRuntime(
        AgentMigrationTaskStore taskStore,
        AgentMigrationPolicy policy,
        boolean recoveryEnabled,
        Duration recoveryScanInterval,
        Duration recoveryLeaseTtl
) {
    BootAgentMigrationRuntime {
        Objects.requireNonNull(taskStore, "taskStore");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(recoveryScanInterval, "recoveryScanInterval");
        Objects.requireNonNull(recoveryLeaseTtl, "recoveryLeaseTtl");
    }

    AgentMigrationCoordinator coordinator(
            AgentLifecycleManager lifecycles,
            AgentDirectory directory,
            AgentMigrationClient client,
            Executor completionExecutor,
            Clock clock
    ) {
        return coordinator(lifecycles, directory, client, completionExecutor,
                AgentMigrationTaskIdGenerator.defaultGenerator(), clock);
    }

    AgentMigrationCoordinator coordinator(
            AgentLifecycleManager lifecycles,
            AgentDirectory directory,
            AgentMigrationClient client,
            Executor completionExecutor,
            Clock clock,
            AgentMigrationSourceHook sourceHook
    ) {
        return coordinator(lifecycles, directory, client, completionExecutor,
                AgentMigrationTaskIdGenerator.defaultGenerator(), clock, sourceHook);
    }

    AgentMigrationCoordinator coordinator(
            AgentLifecycleManager lifecycles,
            AgentDirectory directory,
            AgentMigrationClient client,
            Executor completionExecutor,
            AgentMigrationTaskIdGenerator taskIds,
            Clock clock
    ) {
        return coordinator(lifecycles, directory, client, completionExecutor, taskIds, clock,
                AgentMigrationSourceHook.noop());
    }

    AgentMigrationCoordinator coordinator(
            AgentLifecycleManager lifecycles,
            AgentDirectory directory,
            AgentMigrationClient client,
            Executor completionExecutor,
            AgentMigrationTaskIdGenerator taskIds,
            Clock clock,
            AgentMigrationSourceHook sourceHook
    ) {
        return new AgentMigrationCoordinator(
                lifecycles,
                directory,
                client,
                completionExecutor,
                policy,
                taskStore,
                taskIds,
                clock,
                sourceHook
        );
    }

    AgentMigrationRecoveryService recoveryService(
            AgentDirectory directory,
            AgentLifecycleManager sourceLifecycles,
            AgentMigrationClient client,
            Executor executor,
            Clock clock,
            String recoveryOwner
    ) {
        return new AgentMigrationRecoveryService(
                taskStore,
                directory,
                sourceLifecycles,
                client,
                executor,
                policy,
                clock,
                recoveryOwner,
                recoveryLeaseTtl
        );
    }
}
