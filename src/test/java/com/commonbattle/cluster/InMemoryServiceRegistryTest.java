package com.commonbattle.cluster;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryServiceRegistryTest {
    @Test
    void subscribeReceivesExistingAndFutureServiceChanges() throws Exception {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1");
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1");
        registry.register(game);

        List<RegistryEvent> events = new ArrayList<>();
        AutoCloseable subscription = registry.subscribe(ServiceKind.GAME, events::add);

        registry.register(scene);
        registry.unregister(game.id());
        subscription.close();

        assertEquals(List.of(
                new RegistryEvent(RegistryEventType.REGISTERED, game),
                new RegistryEvent(RegistryEventType.UNREGISTERED, game, 3)
        ), events);
    }

    @Test
    void leasedServiceExpiresAndNotifiesSubscribers() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-02T00:00:00Z"));
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry(clock);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1");
        List<RegistryEvent> events = new ArrayList<>();
        registry.subscribe(ServiceKind.GAME, events::add);

        registry.register(game, Duration.ofSeconds(1));
        assertEquals(0, registry.expireLeases(clock.instant().plusMillis(999)));
        assertEquals(List.of(game), registry.list(ServiceKind.GAME));

        assertEquals(1, registry.expireLeases(clock.instant().plusSeconds(1)));

        assertEquals(List.of(), registry.list(ServiceKind.GAME));
        assertEquals(List.of(
                new RegistryEvent(RegistryEventType.REGISTERED, game, 1),
                new RegistryEvent(RegistryEventType.UNREGISTERED, game, 2)
        ), events);
    }

    @Test
    void snapshotAndReplayUseMonotonicRegistryVersion() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1");
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1");

        registry.register(game);
        registry.register(scene);
        registry.unregister(game.id());

        RegistrySnapshot sceneSnapshot = registry.snapshot(ServiceKind.SCENE);
        List<RegistryEvent> replay = registry.replay(ServiceKind.GAME, 1);

        assertEquals(3, registry.version());
        assertEquals(3, sceneSnapshot.version());
        assertEquals(List.of(scene), sceneSnapshot.services());
        assertEquals(List.of(new RegistryEvent(RegistryEventType.UNREGISTERED, game, 3)), replay);
    }

    @Test
    void historyLimitCompactsOldRegistryEvents() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry(Clock.systemUTC(), 2);
        ServiceDescriptor game1 = descriptor(ServiceKind.GAME, "game-1");
        ServiceDescriptor game2 = descriptor(ServiceKind.GAME, "game-2");
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1");

        registry.register(game1);
        registry.register(game2);
        registry.register(scene);

        assertEquals(3, registry.version());
        assertEquals(1, registry.minReplayVersion());
        assertEquals(List.of(), registry.replay(ServiceKind.GAME, 0));
        assertEquals(1, registry.stats().compactedReplayRequests());
        assertEquals(List.of(new RegistryEvent(RegistryEventType.REGISTERED, game2, 2)), registry.replay(ServiceKind.GAME, 1));
        assertEquals(2, registry.stats().retainedEvents());
    }

    @Test
    void heartbeatExtendsLeaseOnlyForExistingService() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-02T00:00:00Z"));
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry(clock);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1");
        registry.register(game, Duration.ofSeconds(1));

        clock.advance(Duration.ofMillis(700));
        assertTrue(registry.heartbeat(game.id(), Duration.ofSeconds(1)));
        assertEquals(0, registry.expireLeases(Instant.parse("2026-09-02T00:00:01Z")));
        assertEquals(List.of(game), registry.list(ServiceKind.GAME));

        assertEquals(1, registry.expireLeases(Instant.parse("2026-09-02T00:00:02Z")));
        assertFalse(registry.heartbeat(game.id(), Duration.ofSeconds(1)));
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", 9000),
                Set.of(kind.name().toLowerCase() + ".heartbeat"),
                Map.of()
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
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
            return instant;
        }
    }
}
