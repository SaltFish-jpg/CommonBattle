package com.commonbattle.cluster;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterDirectoryTest {
    @Test
    void watchAppliesRegisteredServiceMetadataUpdates() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDescriptor stable = ServiceMetadata.withRouteTag(
                descriptor(ServiceKind.SCENE, "scene-1", Map.of()),
                "stable"
        );
        registry.register(stable);
        ClusterDirectory directory = new ClusterDirectory(registry);

        directory.watch(ServiceKind.SCENE);
        ServiceDescriptor gray = ServiceMetadata.withRouteTag(stable, "gray");
        registry.register(gray);

        assertEquals(1, directory.list(ServiceKind.SCENE).size());
        assertEquals("gray", ServiceMetadata.routeTag(directory.first(ServiceKind.SCENE)));
    }

    @Test
    void watchAppliesDrainingMetadataUpdatesToRoutableSnapshot() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", Map.of());
        registry.register(scene);
        ClusterDirectory directory = new ClusterDirectory(registry);

        directory.watch(ServiceKind.SCENE);
        registry.register(ServiceMetadata.withDraining(scene, true));

        assertEquals(1, directory.list(ServiceKind.SCENE).size());
        assertTrue(directory.routable(ServiceKind.SCENE).isEmpty());
    }

    @Test
    void watchRemovesUnregisteredServiceFromLocalSnapshot() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", Map.of());
        registry.register(scene);
        ClusterDirectory directory = new ClusterDirectory(registry);

        directory.watch(ServiceKind.SCENE);
        registry.unregister(scene.id());

        assertTrue(directory.list(ServiceKind.SCENE).isEmpty());
        assertTrue(directory.routable(ServiceKind.SCENE).isEmpty());
    }

    @Test
    void versionedEventsIgnoreStaleRegistryChanges() {
        ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
        ServiceDescriptor sceneV1 = descriptor(ServiceKind.SCENE, "scene-1", Map.of("load", "1"));
        ServiceDescriptor sceneV2 = descriptor(ServiceKind.SCENE, "scene-1", Map.of("load", "2"));

        directory.accept(new RegistryEvent(RegistryEventType.REGISTERED, sceneV2, 2));
        directory.accept(new RegistryEvent(RegistryEventType.REGISTERED, sceneV1, 1));

        assertEquals(List.of(sceneV2), directory.list(ServiceKind.SCENE));
        assertEquals(2, directory.version(ServiceKind.SCENE));
    }

    @Test
    void replaceSnapshotRepairsStaleLocalDirectory() {
        ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
        ServiceDescriptor stale = descriptor(ServiceKind.SCENE, "scene-1", Map.of());
        ServiceDescriptor current = descriptor(ServiceKind.SCENE, "scene-2", Map.of());
        directory.seed(stale);

        directory.replace(ServiceKind.SCENE, List.of(current), 7);

        assertEquals(List.of(current), directory.list(ServiceKind.SCENE));
        assertEquals(7, directory.version(ServiceKind.SCENE));
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, Map<String, String> metadata) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", 9000),
                Set.of(kind.name().toLowerCase() + ".heartbeat"),
                metadata
        );
    }
}
