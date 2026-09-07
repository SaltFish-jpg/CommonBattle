package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentMigrationTaskRetentionServiceTest {
    @Test
    void purgeUsesRetentionCutoffAndRecordsStats() {
        InMemoryAgentMigrationTaskStore store = new InMemoryAgentMigrationTaskStore();
        store.save(task("migration-1", AgentMigrationTaskStatus.TARGET_ACCEPTED,
                Instant.parse("2026-09-01T00:00:00Z")));
        store.save(task("migration-2", AgentMigrationTaskStatus.ROLLED_BACK,
                Instant.parse("2026-09-01T00:09:00Z")));
        store.save(task("migration-3", AgentMigrationTaskStatus.MOVED,
                Instant.parse("2026-09-01T00:00:00Z")));
        AgentMigrationTaskRetentionService retention = new AgentMigrationTaskRetentionService(
                store,
                Clock.fixed(Instant.parse("2026-09-01T00:10:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5)
        );

        int purged = retention.purge();

        assertEquals(1, purged);
        assertEquals(new AgentMigrationTaskRetentionStats(1, 1, 0), retention.stats());
        assertEquals(List.of("migration-3"), store.pendingTasks().stream().map(AgentMigrationTask::taskId).toList());
    }

    @Test
    void purgeRecordsFailureAndRethrowsStoreError() {
        AgentMigrationTaskRetentionService retention = new AgentMigrationTaskRetentionService(
                new FailingTaskStore(),
                Clock.fixed(Instant.parse("2026-09-01T00:10:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5)
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, retention::purge);

        assertEquals("store unavailable", error.getMessage());
        assertEquals(new AgentMigrationTaskRetentionStats(1, 0, 1), retention.stats());
    }

    private static AgentMigrationTask task(
            String taskId,
            AgentMigrationTaskStatus status,
            Instant updatedAt
    ) {
        return new AgentMigrationTask(
                taskId,
                AgentIdentity.player(10001L),
                new AgentLocation(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new ActorRef("player-10001")),
                new AgentLocation(ServiceId.of(ServiceKind.GAME, "r1", "game-2"), new ActorRef("player-10001")),
                new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1}),
                status,
                "",
                updatedAt
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
            return List.of();
        }

        @Override
        public int purgeTerminalTasksBefore(Instant cutoff) {
            throw new IllegalStateException("store unavailable");
        }

        @Override
        public AgentMigrationTaskStoreStats stats(Instant now) {
            return AgentMigrationTaskStoreStats.empty();
        }
    }
}
