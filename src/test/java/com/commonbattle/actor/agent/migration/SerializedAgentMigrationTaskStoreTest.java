package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializedAgentMigrationTaskStoreTest {
    private final ProtoAgentMigrationTaskSerializer serializer = new ProtoAgentMigrationTaskSerializer();
    private final InMemoryAgentMigrationTaskBytesStore bytesStore = new InMemoryAgentMigrationTaskBytesStore();
    private final SerializedAgentMigrationTaskStore store = new SerializedAgentMigrationTaskStore(bytesStore, serializer);

    @Test
    void claimUsesSerializedCasLease() {
        AgentMigrationTask task = task("migration-1", AgentMigrationTaskStatus.MOVED);
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        store.save(task);

        AgentMigrationTask first = store.claim(task.taskId(), "recovery-1", now, Duration.ofSeconds(30))
                .orElseThrow();

        assertEquals("recovery-1", first.leaseOwner());
        assertTrue(store.claim(task.taskId(), "recovery-2", now.plusSeconds(10), Duration.ofSeconds(30)).isEmpty());
        AgentMigrationTask second = store.claim(task.taskId(), "recovery-2", now.plusSeconds(31),
                Duration.ofSeconds(30)).orElseThrow();
        assertEquals("recovery-2", second.leaseOwner());
        assertEquals(new AgentMigrationTaskStoreStats(1, 1, 0, 1, 0, 1, 1000),
                store.stats(now.plusSeconds(32)));
    }

    @Test
    void markPersistsStatusAndClearsLease() {
        AgentMigrationTask task = task("migration-2", AgentMigrationTaskStatus.PREPARED);
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        store.save(task);
        store.claim(task.taskId(), "recovery-1", now, Duration.ofSeconds(30)).orElseThrow();

        store.mark(task.taskId(), AgentMigrationTaskStatus.ROLLED_BACK, "target failed", now.plusSeconds(1));

        AgentMigrationTask marked = serializer.decode(bytesStore.load(task.taskId()).orElseThrow());
        assertEquals(AgentMigrationTaskStatus.ROLLED_BACK, marked.status());
        assertEquals("target failed", marked.reason());
        assertEquals("", marked.leaseOwner());
        assertEquals(Instant.EPOCH, marked.leaseExpiresAt());
        assertTrue(store.pendingTasks().isEmpty());
    }

    @Test
    void pendingTasksOnlyReturnsPreparedAndMovedTasks() {
        store.save(task("migration-3", AgentMigrationTaskStatus.PREPARED));
        store.save(task("migration-4", AgentMigrationTaskStatus.MOVED));
        store.save(task("migration-5", AgentMigrationTaskStatus.TARGET_ACCEPTED));

        List<AgentMigrationTask> pending = store.pendingTasks();

        assertEquals(2, pending.size());
        assertEquals(List.of("migration-3", "migration-4"), pending.stream().map(AgentMigrationTask::taskId).sorted().toList());
    }

    @Test
    void purgeOnlyDeletesOldTerminalSerializedRecords() {
        Instant old = Instant.parse("2026-09-01T00:00:00Z");
        Instant cutoff = Instant.parse("2026-09-01T00:01:00Z");
        store.save(task("migration-6", AgentMigrationTaskStatus.ROLLED_BACK, old));
        store.save(task("migration-7", AgentMigrationTaskStatus.TARGET_ACCEPTED, cutoff));
        store.save(task("migration-8", AgentMigrationTaskStatus.PREPARED, old));

        int purged = store.purgeTerminalTasksBefore(cutoff);

        assertEquals(1, purged);
        assertTrue(bytesStore.load("migration-6").isEmpty());
        assertTrue(bytesStore.load("migration-7").isPresent());
        assertTrue(bytesStore.load("migration-8").isPresent());
        assertEquals(List.of("migration-8"), store.pendingTasks().stream().map(AgentMigrationTask::taskId).toList());
    }

    private static AgentMigrationTask task(String taskId, AgentMigrationTaskStatus status) {
        return task(taskId, status, Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static AgentMigrationTask task(
            String taskId,
            AgentMigrationTaskStatus status,
            Instant updatedAt
    ) {
        AgentIdentity player = AgentIdentity.player(10001L);
        return new AgentMigrationTask(
                taskId,
                player,
                new AgentLocation(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new ActorRef("player-10001")),
                new AgentLocation(ServiceId.of(ServiceKind.GAME, "r1", "game-2"), new ActorRef("player-10001")),
                new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1, 2, 3}),
                status,
                "",
                updatedAt
        );
    }
}
