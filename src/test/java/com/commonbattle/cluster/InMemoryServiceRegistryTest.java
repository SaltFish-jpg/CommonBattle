package com.commonbattle.cluster;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                new RegistryEvent(RegistryEventType.UNREGISTERED, game)
        ), events);
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", 9000),
                Set.of(kind.name().toLowerCase() + ".heartbeat"),
                Map.of()
        );
    }
}
