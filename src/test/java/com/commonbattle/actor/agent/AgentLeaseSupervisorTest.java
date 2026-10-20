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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLeaseSupervisorTest {
    @Test
    void renewsOwnedLeasesAndReportsConfirmedTakeover() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SerializedAgentDirectory directory = new SerializedAgentDirectory(new InMemoryAtomicBytesStore(), clock);
        List<AgentDirectoryLease> losses = new ArrayList<>();
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation firstLocation = location("game-1");
        try (AgentLeaseSupervisor supervisor = new AgentLeaseSupervisor(
                directory,
                "game-1-session",
                Duration.ofSeconds(10),
                Duration.ofSeconds(3),
                clock,
                losses::add
        )) {
            AgentDirectoryLease first = supervisor.acquire(player, firstLocation).orElseThrow();
            clock.advance(Duration.ofSeconds(3));

            assertEquals(1, supervisor.renewOnce());
            AgentDirectoryLease renewed = supervisor.lease(player).orElseThrow();
            assertTrue(renewed.expiresAt().isAfter(first.expiresAt()));

            clock.advance(Duration.ofSeconds(10));
            AgentDirectoryLease takeover = directory.acquire(
                    player, location("game-2"), "game-2-session", Duration.ofSeconds(10)
            ).orElseThrow();

            assertEquals(0, supervisor.renewOnce());
            assertEquals(2, takeover.fencingToken());
            assertTrue(supervisor.lease(player).isEmpty());
            assertEquals(List.of(renewed), losses);
            assertEquals(1, supervisor.stats().lostLeases());
        }
    }

    @Test
    void transientRenewFailureOnlyFencesAfterLocalExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SerializedAgentDirectory delegate = new SerializedAgentDirectory(new InMemoryAtomicBytesStore(), clock);
        FailingRenewDirectory directory = new FailingRenewDirectory(delegate);
        List<AgentDirectoryLease> losses = new ArrayList<>();
        AgentIdentity player = AgentIdentity.player(10001L);
        try (AgentLeaseSupervisor supervisor = new AgentLeaseSupervisor(
                directory,
                "game-1-session",
                Duration.ofSeconds(10),
                Duration.ofSeconds(3),
                clock,
                losses::add
        )) {
            supervisor.acquire(player, location("game-1")).orElseThrow();
            directory.failRenew = true;

            clock.advance(Duration.ofSeconds(3));
            supervisor.renewOnce();
            assertTrue(supervisor.lease(player).isPresent());
            assertTrue(losses.isEmpty());

            clock.advance(Duration.ofSeconds(7));
            supervisor.renewOnce();
            assertTrue(supervisor.lease(player).isEmpty());
            assertEquals(1, losses.size());
            assertEquals(2, supervisor.stats().renewalFailures());
        }
    }

    private static AgentLocation location(String node) {
        return new AgentLocation(
                ServiceId.of(ServiceKind.GAME, "r1", node),
                new ActorRef("player-10001")
        );
    }

    private static final class FailingRenewDirectory implements LeasedAgentDirectory {
        private final LeasedAgentDirectory delegate;
        private boolean failRenew;

        private FailingRenewDirectory(LeasedAgentDirectory delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<AgentDirectoryLease> acquire(
                AgentIdentity identity,
                AgentLocation location,
                String ownerSession,
                Duration leaseTtl
        ) {
            return delegate.acquire(identity, location, ownerSession, leaseTtl);
        }

        @Override
        public Optional<AgentDirectoryLease> renew(AgentDirectoryLease lease, Duration leaseTtl) {
            if (failRenew) {
                throw new IllegalStateException("center unavailable");
            }
            return delegate.renew(lease, leaseTtl);
        }

        @Override
        public Optional<AgentDirectoryLease> transfer(
                AgentDirectoryLease lease,
                AgentLocation nextLocation,
                String nextOwnerSession,
                Duration leaseTtl
        ) {
            return delegate.transfer(lease, nextLocation, nextOwnerSession, leaseTtl);
        }

        @Override
        public boolean release(AgentDirectoryLease lease) {
            return delegate.release(lease);
        }

        @Override
        public Optional<AgentDirectoryLease> locateLease(AgentIdentity identity) {
            return delegate.locateLease(identity);
        }

        @Override
        public boolean claim(AgentIdentity identity, AgentLocation location) {
            return delegate.claim(identity, location);
        }

        @Override
        public boolean move(AgentIdentity identity, AgentLocation expectedCurrent, AgentLocation next) {
            return delegate.move(identity, expectedCurrent, next);
        }

        @Override
        public void unbind(AgentIdentity identity, AgentLocation location) {
            delegate.unbind(identity, location);
        }

        @Override
        public Optional<AgentLocation> locate(AgentIdentity identity) {
            return delegate.locate(identity);
        }
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
