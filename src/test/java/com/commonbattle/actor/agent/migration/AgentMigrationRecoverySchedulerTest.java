package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentMigrationRecoverySchedulerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void recoverOnceDelegatesToRecoveryService() {
        AgentMigrationRecoveryService recovery = recovery(AgentMigrationTaskStore.none());
        AgentMigrationRecoveryScheduler scheduler = new AgentMigrationRecoveryScheduler(
                recovery,
                ignored -> {
                },
                Duration.ofSeconds(1)
        );

        int recovered = scheduler.recoverOnce();

        assertEquals(0, recovered);
        assertEquals(new AgentMigrationRecoverySchedulerStats(1, 0, 0), scheduler.stats());
        assertEquals(1, recovery.stats().scans());
    }

    @Test
    void scheduledRecoverySwallowsScanFailure() {
        AgentMigrationRecoveryService recovery = recovery(new FailingTaskStore());
        AgentMigrationRecoveryScheduler scheduler = new AgentMigrationRecoveryScheduler(
                recovery,
                ignored -> {
                },
                Duration.ofSeconds(1)
        );

        assertDoesNotThrow(scheduler::recoverSafely);
        assertEquals(new AgentMigrationRecoverySchedulerStats(1, 0, 1), scheduler.stats());
        assertEquals(1, recovery.stats().scans());
    }

    private static AgentMigrationRecoveryService recovery(AgentMigrationTaskStore store) {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        return new AgentMigrationRecoveryService(
                store,
                directory,
                new AgentLifecycleManager(local, actors, directory, CLOCK),
                (target, request) -> AgentMigrationAcceptResponse.success(),
                Runnable::run,
                AgentMigrationPolicy.defaults(),
                CLOCK
        );
    }

    private static final class FailingTaskStore implements AgentMigrationTaskStore {
        @Override
        public void save(AgentMigrationTask task) {
        }

        @Override
        public Optional<AgentMigrationTask> claim(String taskId, String owner, Instant now, Duration leaseTtl) {
            return Optional.empty();
        }

        @Override
        public void mark(String taskId, AgentMigrationTaskStatus status, String reason, Instant now) {
        }

        @Override
        public List<AgentMigrationTask> pendingTasks() {
            throw new IllegalStateException("store unavailable");
        }

        @Override
        public int purgeTerminalTasksBefore(Instant cutoff) {
            return 0;
        }

        @Override
        public AgentMigrationTaskStoreStats stats(Instant now) {
            return AgentMigrationTaskStoreStats.empty();
        }
    }
}
