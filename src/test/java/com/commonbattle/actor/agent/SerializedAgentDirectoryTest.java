package com.commonbattle.actor.agent;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializedAgentDirectoryTest {
    @Test
    void coordinatesClaimMoveAndLocateAcrossDirectoryInstances() {
        InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
        SerializedAgentDirectory first = new SerializedAgentDirectory(bytes);
        SerializedAgentDirectory second = new SerializedAgentDirectory(bytes);
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation gameOne = location("game-1", "player-10001");
        AgentLocation gameTwo = location("game-2", "player-10001-migrated");

        assertTrue(first.claim(player, gameOne));
        assertTrue(second.claim(player, gameOne));
        assertFalse(second.claim(player, gameTwo));
        assertTrue(second.move(player, gameOne, gameTwo));
        assertEquals(gameTwo, first.locate(player).orElseThrow());
        assertFalse(first.move(player, gameOne, gameOne));
    }

    @Test
    void staleUnbindCannotRemoveNewOwnerAfterMigrationOrReconstruction() {
        InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
        SerializedAgentDirectory beforeRestart = new SerializedAgentDirectory(bytes);
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation oldOwner = location("game-1", "player-10001");
        AgentLocation newOwner = location("game-2", "player-10001");
        beforeRestart.claim(player, oldOwner);
        beforeRestart.move(player, oldOwner, newOwner);

        SerializedAgentDirectory recovered = new SerializedAgentDirectory(bytes);
        recovered.unbind(player, oldOwner);

        assertEquals(newOwner, recovered.locate(player).orElseThrow());
        recovered.unbind(player, newOwner);
        assertTrue(recovered.locate(player).isEmpty());
    }

    @Test
    void expiredLeaseCanBeTakenOverWithHigherFencingToken() {
        InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SerializedAgentDirectory first = new SerializedAgentDirectory(bytes, clock);
        SerializedAgentDirectory second = new SerializedAgentDirectory(bytes, clock);
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentDirectoryLease oldLease = first.acquire(
                player, location("game-1", "player-10001"), "game-1-session-a", Duration.ofSeconds(10)
        ).orElseThrow();

        assertEquals(1, oldLease.fencingToken());
        assertTrue(second.acquire(
                player, location("game-2", "player-10001"), "game-2-session-a", Duration.ofSeconds(10)
        ).isEmpty());

        clock.advance(Duration.ofSeconds(10));
        assertTrue(first.locate(player).isEmpty());
        AgentDirectoryLease newLease = second.acquire(
                player, location("game-2", "player-10001"), "game-2-session-a", Duration.ofSeconds(10)
        ).orElseThrow();

        assertEquals(2, newLease.fencingToken());
        assertEquals(newLease.location(), first.locate(player).orElseThrow());
        assertTrue(first.renew(oldLease, Duration.ofSeconds(10)).isEmpty());
        assertFalse(first.release(oldLease));
    }

    @Test
    void renewalAndReleaseRequireTheLatestLeaseRevision() {
        InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SerializedAgentDirectory directory = new SerializedAgentDirectory(bytes, clock);
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentDirectoryLease acquired = directory.acquire(
                player, location("game-1", "player-10001"), "game-1-session-a", Duration.ofSeconds(10)
        ).orElseThrow();

        clock.advance(Duration.ofSeconds(3));
        AgentDirectoryLease renewed = directory.renew(acquired, Duration.ofSeconds(10)).orElseThrow();

        assertTrue(renewed.expiresAt().isAfter(acquired.expiresAt()));
        assertFalse(directory.release(acquired));
        assertTrue(directory.release(renewed));
        assertTrue(directory.locateLease(player).isEmpty());
        AgentDirectoryLease reacquired = directory.acquire(
                player, location("game-1", "player-10001"), "game-1-session-b", Duration.ofSeconds(10)
        ).orElseThrow();
        assertEquals(2, reacquired.fencingToken());
    }

    @Test
    void transferMovesOwnerAndAdvancesFencingToken() {
        InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SerializedAgentDirectory directory = new SerializedAgentDirectory(bytes, clock);
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentDirectoryLease source = directory.acquire(
                player, location("game-1", "player-10001"), "game-1-session", Duration.ofSeconds(10)
        ).orElseThrow();

        AgentDirectoryLease handoff = directory.transfer(
                source,
                location("game-2", "player-10001"),
                "handoff:migration-1",
                Duration.ofSeconds(10)
        ).orElseThrow();

        assertEquals(2, handoff.fencingToken());
        assertEquals("game-2", handoff.location().serviceId().node());
        assertTrue(directory.renew(source, Duration.ofSeconds(10)).isEmpty());
        assertTrue(directory.transfer(
                source,
                location("game-1", "player-10001"),
                "game-1-session",
                Duration.ofSeconds(10)
        ).isEmpty());
    }

    private static AgentLocation location(String node, String actorId) {
        return new AgentLocation(
                ServiceId.of(ServiceKind.GAME, "r1", node),
                new ActorRef(actorId)
        );
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
