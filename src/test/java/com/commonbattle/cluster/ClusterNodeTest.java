package com.commonbattle.cluster;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(kind.name().toLowerCase() + ".heartbeat"),
                Map.of()
        );
    }
}
