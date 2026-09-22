package com.commonbattle.observability;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSlowTask;
import com.commonbattle.actor.ActorTaskCategory;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryActorSlowTaskLogTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void recordsSlowTasksWithAggregateStats() {
        InMemoryActorSlowTaskLog log = new InMemoryActorSlowTaskLog(4, CLOCK);

        log.accept(new ActorSlowTask(new ActorRef("player-10001"), ActorTaskCategory.PLAYER_COMMAND,
                Duration.ofMillis(15), Duration.ofMillis(10)));
        log.accept(new ActorSlowTask(new ActorRef("scene-shard:world-1:0"), ActorTaskCategory.TIMER,
                Duration.ofMillis(30), Duration.ofMillis(10)));

        ActorSlowTaskStats stats = log.actorSlowTaskStats();
        assertEquals(2, stats.retainedEntries());
        assertEquals(2, stats.recordedEntries());
        assertEquals(0, stats.droppedEntries());
        assertEquals(30, stats.maxElapsedMillis());
        assertEquals("scene-shard:world-1:0", stats.maxElapsedActorId());
        assertEquals(ActorTaskCategory.TIMER, stats.maxElapsedCategory());
        assertEquals(1, stats.byCategory().get(ActorTaskCategory.PLAYER_COMMAND));
        assertEquals(1, stats.byCategory().get(ActorTaskCategory.TIMER));
        assertEquals(CLOCK.instant(), log.recentActorSlowTasks().getFirst().at());
    }

    @Test
    void evictsOldestRecordButKeepsAggregateStats() {
        InMemoryActorSlowTaskLog log = new InMemoryActorSlowTaskLog(1, CLOCK);

        log.accept(new ActorSlowTask(new ActorRef("player-10001"), ActorTaskCategory.PLAYER_COMMAND,
                Duration.ofMillis(15), Duration.ofMillis(10)));
        log.accept(new ActorSlowTask(new ActorRef("player-10002"), ActorTaskCategory.RPC_CALLBACK,
                Duration.ofMillis(20), Duration.ofMillis(10)));

        ActorSlowTaskStats stats = log.actorSlowTaskStats();
        assertEquals(1, log.size());
        assertEquals(2, stats.recordedEntries());
        assertEquals(1, stats.droppedEntries());
        assertEquals("player-10002", log.recentActorSlowTasks().getFirst().actorId());
        assertEquals(1, stats.byCategory().get(ActorTaskCategory.PLAYER_COMMAND));
        assertEquals(1, stats.byCategory().get(ActorTaskCategory.RPC_CALLBACK));
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryActorSlowTaskLog(0, CLOCK));
    }
}
