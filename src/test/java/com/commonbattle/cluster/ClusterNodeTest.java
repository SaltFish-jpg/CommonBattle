package com.commonbattle.cluster;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterNodeTest {
    @Test
    void nodeRegistersItselfAndSubscribesWatchedServices() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9100);
        registry.register(scene);
        ClusterNode game = new ClusterNode(registry, descriptor(ServiceKind.GAME, "game-1", 9001));

        game.start(List.of(ServiceKind.SCENE, ServiceKind.PROXY));

        assertEquals(List.of(scene), game.directory().list(ServiceKind.SCENE));
        assertEquals(1, registry.list(ServiceKind.GAME).size());

        game.close();
        assertEquals(0, registry.list(ServiceKind.GAME).size());
    }

    @Test
    void nodeCanRegisterItselfWithLeaseRenewer() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry(clock);
        ClusterNode game = new ClusterNode(registry, descriptor(ServiceKind.GAME, "game-1", 9001));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            game.start(List.of(ServiceKind.SCENE), Duration.ofSeconds(1), Duration.ofSeconds(60), scheduler);

            assertTrue(game.leaseRenewer().isPresent());
            assertEquals(1, registry.list(ServiceKind.GAME).size());
            assertEquals(1, registry.expireLeases(clock.instant().plusSeconds(2)));
            assertEquals(0, registry.list(ServiceKind.GAME).size());
        } finally {
            game.close();
            scheduler.shutdownNow();
        }
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(kind.name().toLowerCase() + ".heartbeat"),
                Map.of()
        );
    }
}
