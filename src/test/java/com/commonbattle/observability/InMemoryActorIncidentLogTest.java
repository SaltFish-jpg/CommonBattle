package com.commonbattle.observability;

import com.commonbattle.actor.ActorFailure;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.DeadLetter;
import com.commonbattle.actor.DeadLetterReason;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryActorIncidentLogTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void recordsDeadLettersAndPoisonMessagesWithAggregateStats() {
        InMemoryActorIncidentLog log = new InMemoryActorIncidentLog(4, CLOCK);
        ActorRef player = new ActorRef("player-10001");
        ActorTask command = ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND, ignored -> {
        });

        log.accept(new DeadLetter(player, command, DeadLetterReason.MAILBOX_FULL));
        log.onFailure(new ActorFailure(player, ActorTask.categorized(ActorTaskCategory.RPC_CALLBACK, ignored -> {
        }), new IllegalStateException("boom")));

        ActorIncidentStats stats = log.actorIncidentStats();
        assertEquals(2, stats.recordedEntries());
        assertEquals(2, stats.retainedEntries());
        assertEquals(1, stats.deadLetters());
        assertEquals(1, stats.poisonMessages());
        assertEquals(1, stats.byKind().get(ActorIncidentKind.DEAD_LETTER));
        assertEquals(1, stats.byKind().get(ActorIncidentKind.POISON_MESSAGE));
        assertEquals(1, stats.byCategory().get(ActorTaskCategory.PLAYER_COMMAND));
        assertEquals(1, stats.byCategory().get(ActorTaskCategory.RPC_CALLBACK));
        assertEquals(1, stats.byReason().get("MAILBOX_FULL"));
        assertEquals(1, stats.byReason().get("TASK_FAILED"));
        assertEquals("java.lang.IllegalStateException", log.recentActorIncidents().get(1).errorType());
        assertEquals(CLOCK.instant(), log.recentActorIncidents().get(1).at());
    }

    @Test
    void evictsOldestRecordButKeepsAggregateStats() {
        InMemoryActorIncidentLog log = new InMemoryActorIncidentLog(1, CLOCK);
        ActorRef player = new ActorRef("player-10001");

        log.accept(new DeadLetter(player, ignored -> {
        }, DeadLetterReason.SYSTEM_CLOSED));
        log.onFailure(new ActorFailure(player, ignored -> {
        }, new IllegalArgumentException("bad")));

        ActorIncidentStats stats = log.actorIncidentStats();
        assertEquals(1, log.size());
        assertEquals(2, stats.recordedEntries());
        assertEquals(1, stats.deadLetters());
        assertEquals(1, stats.poisonMessages());
        assertEquals(1, stats.droppedEntries());
        assertEquals(ActorIncidentKind.POISON_MESSAGE, log.recentActorIncidents().getFirst().kind());
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryActorIncidentLog(0, CLOCK));
    }
}
