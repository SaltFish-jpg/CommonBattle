package com.commonbattle.observability;

import com.commonbattle.actor.ActorMailboxStats;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTaskCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActorHotspotAnalyzerTest {
    @Test
    void combinesMailboxAndSlowTaskSignalsIntoOrderedActions() {
        ActorHotspotPolicy policy = new ActorHotspotPolicy(1, 3, 5, 2, 3, 100, 500);

        List<ActorHotspotCandidate> candidates = ActorHotspotAnalyzer.analyze(
                List.of(
                        new ActorMailboxStats(new ActorRef("player-10001"), 1, Map.of()),
                        new ActorMailboxStats(new ActorRef("player-10002"), 3, Map.of()),
                        new ActorMailboxStats(new ActorRef("scene-shard:world-1:0"), 5, Map.of())
                ),
                List.of(
                        new ActorSlowTaskRecord("chat-world", ActorTaskCategory.EVENT, 120, 10, Instant.EPOCH),
                        new ActorSlowTaskRecord("player-10002", ActorTaskCategory.RPC_CALLBACK, 80, 10, Instant.EPOCH)
                ),
                policy
        );

        assertEquals(4, candidates.size());
        assertEquals("scene-shard:world-1:0", candidates.get(0).actorId());
        assertEquals(ActorHotspotAction.MIGRATION_CANDIDATE, candidates.get(0).action());
        assertEquals("player-10002", candidates.get(1).actorId());
        assertEquals(ActorHotspotAction.THROTTLE, candidates.get(1).action());
        assertEquals("chat-world", candidates.get(2).actorId());
        assertEquals(ActorHotspotAction.THROTTLE, candidates.get(2).action());
        assertEquals("player-10001", candidates.get(3).actorId());
        assertEquals(ActorHotspotAction.OBSERVE, candidates.get(3).action());
    }
}
