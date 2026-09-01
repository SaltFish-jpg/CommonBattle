package com.commonbattle.actor.agent;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAgentDirectoryTest {
    @Test
    void claimKeepsSingleOwnerForSameAgentIdentity() {
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        AgentIdentity identity = AgentIdentity.player(10001L);
        AgentLocation game1 = location("game-1", "player-10001");
        AgentLocation game2 = location("game-2", "player-10001");

        assertTrue(directory.claim(identity, game1));
        assertFalse(directory.claim(identity, game2));

        assertEquals(game1, directory.locate(identity).orElseThrow());
    }

    @Test
    void moveRequiresExpectedCurrentLocation() {
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        AgentIdentity identity = AgentIdentity.player(10001L);
        AgentLocation game1 = location("game-1", "player-10001");
        AgentLocation game2 = location("game-2", "player-10001");
        AgentLocation stale = location("game-stale", "player-10001");
        directory.claim(identity, game1);

        assertFalse(directory.move(identity, stale, game2));
        assertEquals(game1, directory.locate(identity).orElseThrow());

        assertTrue(directory.move(identity, game1, game2));
        assertEquals(game2, directory.locate(identity).orElseThrow());
    }

    @Test
    void unbindOnlyRemovesMatchingLocation() {
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        AgentIdentity identity = AgentIdentity.player(10001L);
        AgentLocation game1 = location("game-1", "player-10001");
        AgentLocation stale = location("game-stale", "player-10001");
        directory.claim(identity, game1);

        directory.unbind(identity, stale);
        assertTrue(directory.locate(identity).isPresent());

        directory.unbind(identity, game1);
        assertTrue(directory.locate(identity).isEmpty());
    }

    private static AgentLocation location(String node, String actorId) {
        return new AgentLocation(
                ServiceId.of(ServiceKind.GAME, "r1", node),
                new ActorRef(actorId)
        );
    }
}
