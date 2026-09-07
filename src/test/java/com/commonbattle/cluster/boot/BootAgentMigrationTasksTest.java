package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.migration.AgentMigrationAcceptResponse;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoveryService;
import com.commonbattle.actor.agent.migration.AgentMigrationSnapshot;
import com.commonbattle.actor.agent.migration.AgentMigrationTask;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStore;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStatus;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.actor.agent.migration.SerializedAgentMigrationTaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class BootAgentMigrationTasksTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void configureCreatesSerializedStoreAndRegistersRetentionServiceWhenEnabled() {
        BootRuntime runtime = new BootRuntime();
        try {
            ClusterNodeConfig config = ClusterNodeConfig.fromProperties(base());

            AgentMigrationTaskStore store = BootAgentMigrationTasks.configure(runtime, config, CLOCK);

            assertInstanceOf(SerializedAgentMigrationTaskStore.class, store);
            assertEquals(1, runtime.healthRegistry().migrationTaskStores().size());
            assertEquals(1, runtime.healthRegistry().migrationTaskRetentions().size());
        } finally {
            runtime.close();
        }
    }

    @Test
    void configureRuntimeExposesMigrationRecoverySettings() {
        BootRuntime runtime = new BootRuntime();
        try {
            Properties properties = base();
            properties.setProperty("cluster.migration.recovery.enabled", "false");
            properties.setProperty("cluster.migration.recovery.scan.interval.millis", "7000");
            properties.setProperty("cluster.migration.recovery.lease.ttl.millis", "45000");
            properties.setProperty("cluster.migration.recovery.target.accept.attempts", "3");
            ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

            BootAgentMigrationRuntime migration = BootAgentMigrationTasks.configureRuntime(runtime, config, CLOCK);

            assertInstanceOf(SerializedAgentMigrationTaskStore.class, migration.taskStore());
            assertEquals(false, migration.recoveryEnabled());
            assertEquals(7_000, migration.recoveryScanInterval().toMillis());
            assertEquals(45_000, migration.recoveryLeaseTtl().toMillis());
            assertEquals(3, migration.policy().targetAcceptAttempts());
        } finally {
            runtime.close();
        }
    }

    @Test
    void configuredRuntimeBuildsRecoveryServiceWithBootLeaseAndPolicy() {
        BootRuntime runtime = new BootRuntime();
        try {
            Properties properties = base();
            properties.setProperty("cluster.migration.recovery.lease.ttl.millis", "45000");
            properties.setProperty("cluster.migration.recovery.target.accept.attempts", "3");
            ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);
            BootAgentMigrationRuntime migration = BootAgentMigrationTasks.configureRuntime(runtime, config, CLOCK);
            ActorSystem actors = new ActorSystem(Runnable::run, 64);
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
            RecordingExecutor executor = new RecordingExecutor();
            AgentMigrationRecoveryService recovery = migration.recoveryService(
                    directory,
                    lifecycles,
                    (target, request) -> AgentMigrationAcceptResponse.rejected("defer"),
                    executor,
                    CLOCK,
                    "boot-recovery"
            );
            AgentMigrationTask task = task("migration-runtime-1");
            migration.taskStore().save(task);

            int claimed = recovery.recoverPending(ignored -> {
            });

            AgentMigrationTask leased = migration.taskStore().pendingTasks().getFirst();
            assertEquals(1, claimed);
            assertEquals(1, executor.pending());
            assertEquals("boot-recovery", leased.leaseOwner());
            assertEquals(CLOCK.instant().plusMillis(45_000), leased.leaseExpiresAt());
        } finally {
            runtime.close();
        }
    }

    @Test
    void configureCanDisableRetentionScheduler() {
        BootRuntime runtime = new BootRuntime();
        try {
            Properties properties = base();
            properties.setProperty("cluster.migration.task.retention.enabled", "false");
            ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

            AgentMigrationTaskStore store = BootAgentMigrationTasks.configure(runtime, config, CLOCK);

            assertInstanceOf(SerializedAgentMigrationTaskStore.class, store);
            assertEquals(1, runtime.healthRegistry().migrationTaskStores().size());
            assertEquals(0, runtime.healthRegistry().migrationTaskRetentions().size());
        } finally {
            runtime.close();
        }
    }

    @Test
    void configureCanUseFileBackedTaskStore(@TempDir Path directory) {
        Properties properties = base();
        properties.setProperty("cluster.migration.task.store", "FILE");
        properties.setProperty("cluster.migration.task.store.dir", directory.toString());
        properties.setProperty("cluster.migration.task.retention.enabled", "false");
        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);
        BootRuntime firstRuntime = new BootRuntime();
        BootRuntime secondRuntime = new BootRuntime();
        try {
            AgentMigrationTaskStore first = BootAgentMigrationTasks.configure(firstRuntime, config, CLOCK);
            first.save(task("migration-file-1"));

            AgentMigrationTaskStore second = BootAgentMigrationTasks.configure(secondRuntime, config, CLOCK);

            assertEquals(1, second.pendingTasks().size());
            assertEquals("migration-file-1", second.pendingTasks().getFirst().taskId());
        } finally {
            firstRuntime.close();
            secondRuntime.close();
        }
    }

    @Test
    void configureCanCreateJdbcStoreWithoutSchemaInitialization() {
        BootRuntime runtime = new BootRuntime();
        try {
            Properties properties = base();
            properties.setProperty("cluster.migration.task.store", "JDBC");
            properties.setProperty("cluster.migration.task.jdbc.url", "jdbc:fake:migration");
            properties.setProperty("cluster.migration.task.jdbc.initialize.schema", "false");
            properties.setProperty("cluster.migration.task.retention.enabled", "false");
            ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

            AgentMigrationTaskStore store = BootAgentMigrationTasks.configure(runtime, config, CLOCK);

            assertInstanceOf(SerializedAgentMigrationTaskStore.class, store);
            assertEquals(1, runtime.healthRegistry().migrationTaskStores().size());
        } finally {
            runtime.close();
        }
    }

    @Test
    void configureRecoveryRegistersServiceAndSchedulerWhenEnabled() {
        BootRuntime runtime = new BootRuntime();
        try {
            ClusterNodeConfig config = ClusterNodeConfig.fromProperties(base());
            AgentMigrationRecoveryService recovery = recovery();

            BootAgentMigrationTasks.configureRecovery(runtime, config, recovery, ignored -> {
            });

            assertEquals(1, runtime.healthRegistry().migrationRecoveries().size());
            assertEquals(1, runtime.healthRegistry().migrationRecoverySchedulers().size());
        } finally {
            runtime.close();
        }
    }

    @Test
    void configureRecoveryCanDisableScheduler() {
        BootRuntime runtime = new BootRuntime();
        try {
            Properties properties = base();
            properties.setProperty("cluster.migration.recovery.enabled", "false");
            ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);
            AgentMigrationRecoveryService recovery = recovery();

            BootAgentMigrationTasks.configureRecovery(runtime, config, recovery, ignored -> {
            });

            assertEquals(1, runtime.healthRegistry().migrationRecoveries().size());
            assertEquals(0, runtime.healthRegistry().migrationRecoverySchedulers().size());
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
        properties.setProperty("cluster.migration.task.retention.enabled", "true");
        properties.setProperty("cluster.migration.task.retention.millis", "86400000");
        properties.setProperty("cluster.migration.task.retention.scan.interval.millis", "60000");
        properties.setProperty("cluster.migration.recovery.enabled", "true");
        properties.setProperty("cluster.migration.recovery.scan.interval.millis", "5000");
        properties.setProperty("cluster.migration.recovery.lease.ttl.millis", "30000");
        properties.setProperty("cluster.migration.recovery.target.accept.attempts", "1");
        return properties;
    }

    private static AgentMigrationTask task(String taskId) {
        return new AgentMigrationTask(
                taskId,
                AgentIdentity.player(10001L),
                new AgentLocation(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new ActorRef("player-10001")),
                new AgentLocation(ServiceId.of(ServiceKind.GAME, "r1", "game-2"), new ActorRef("player-10001")),
                new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1}),
                AgentMigrationTaskStatus.MOVED,
                "",
                CLOCK.instant()
        );
    }

    private static AgentMigrationRecoveryService recovery() {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        return new AgentMigrationRecoveryService(
                AgentMigrationTaskStore.none(),
                directory,
                new AgentLifecycleManager(local, actors, directory, CLOCK),
                (target, request) -> AgentMigrationAcceptResponse.success(),
                Runnable::run,
                new com.commonbattle.actor.agent.migration.AgentMigrationPolicy(1),
                CLOCK
        );
    }

    private static final class RecordingExecutor implements java.util.concurrent.Executor {
        private int pending;

        @Override
        public void execute(Runnable command) {
            pending++;
        }

        int pending() {
            return pending;
        }
    }
}
