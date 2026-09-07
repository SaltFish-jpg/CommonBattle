package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAgentMigrationTaskStoreTest {
    @Test
    void claimAllowsOnlyOneOwnerUntilLeaseExpires() {
        InMemoryAgentMigrationTaskStore store = new InMemoryAgentMigrationTaskStore();
        AgentMigrationTask task = task("migration-1");
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
    void purgeOnlyRemovesTerminalTasksBeforeCutoff() {
        InMemoryAgentMigrationTaskStore store = new InMemoryAgentMigrationTaskStore();
        Instant old = Instant.parse("2026-09-01T00:00:00Z");
        Instant cutoff = Instant.parse("2026-09-01T00:01:00Z");
        store.save(task("migration-2", AgentMigrationTaskStatus.ROLLED_BACK, old));
        store.save(task("migration-3", AgentMigrationTaskStatus.TARGET_ACCEPTED, cutoff));
        store.save(task("migration-4", AgentMigrationTaskStatus.MOVED, old));

        int purged = store.purgeTerminalTasksBefore(cutoff);

        assertEquals(1, purged);
        assertEquals("migration-4", store.pendingTasks().getFirst().taskId());
        assertEquals("recovery-1", store.claim("migration-4", "recovery-1", cutoff, Duration.ofSeconds(30))
                .orElseThrow().leaseOwner());
    }

    private static AgentMigrationTask task(String taskId) {
        return task(taskId, AgentMigrationTaskStatus.MOVED, Instant.parse("2026-09-01T00:00:00Z"));
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
                new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1}),
                status,
                "",
                updatedAt
        );
    }
}
