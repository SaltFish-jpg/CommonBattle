package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.RegistryEvent;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CenterRegistryEndpointTest {
    @Test
    void remoteRegistryRegistersAndReceivesSubscriptionEventsFromCenter() {
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        InMemoryServiceRegistry centerStorage = new InMemoryServiceRegistry();
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, Set.of(
                RegistryOperations.REGISTER,
                RegistryOperations.HEARTBEAT,
                RegistryOperations.UNREGISTER,
                RegistryOperations.LIST,
                RegistryOperations.SUBSCRIBE
        ));
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of("scene.enter"));
        centerStorage.register(center);

        ClusterDirectory centerDirectory = new ClusterDirectory(centerStorage);
        for (ServiceKind kind : ServiceKind.values()) {
            centerDirectory.watch(kind);
        }
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, centerDirectory, topology, transport);
        new CenterRegistryEndpoint(center, centerStorage, transport, centerGateway);

        ClusterDirectory gameDirectory = new ClusterDirectory(new InMemoryServiceRegistry());
        gameDirectory.seed(center);
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        RemoteServiceRegistry gameRegistry = new RemoteServiceRegistry(game.id(), gameGateway, gameDirectory);

        gameRegistry.register(game);
        gameRegistry.subscribe(ServiceKind.SCENE, event -> {
        });
        assertEquals(List.of(), gameRegistry.list(ServiceKind.SCENE));

        ClusterDirectory sceneDirectory = new ClusterDirectory(new InMemoryServiceRegistry());
        sceneDirectory.seed(center);
        ClusterRpcGateway sceneGateway = new ClusterRpcGateway(scene, sceneDirectory, topology, transport);
        RemoteServiceRegistry sceneRegistry = new RemoteServiceRegistry(scene.id(), sceneGateway, sceneDirectory);
        sceneRegistry.register(scene);

        assertEquals(List.of(scene), gameRegistry.list(ServiceKind.SCENE));
        assertEquals(List.of(scene), gameDirectory.list(ServiceKind.SCENE));
    }

    @Test
    void remoteRegistryRenewsLeaseAndReceivesExpireEventFromCenter() {
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        InMemoryServiceRegistry centerStorage = new InMemoryServiceRegistry();
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, Set.of(
                RegistryOperations.REGISTER,
                RegistryOperations.HEARTBEAT,
                RegistryOperations.UNREGISTER,
                RegistryOperations.LIST,
                RegistryOperations.SUBSCRIBE
        ));
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of("scene.enter"));
        centerStorage.register(center);
        ClusterDirectory centerDirectory = new ClusterDirectory(centerStorage);
        for (ServiceKind kind : ServiceKind.values()) {
            centerDirectory.watch(kind);
        }
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, centerDirectory, topology, transport);
        new CenterRegistryEndpoint(center, centerStorage, transport, centerGateway);

        ClusterDirectory gameDirectory = new ClusterDirectory(new InMemoryServiceRegistry());
        gameDirectory.seed(center);
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        RemoteServiceRegistry gameRegistry = new RemoteServiceRegistry(game.id(), gameGateway, gameDirectory);
        gameRegistry.register(game, Duration.ofSeconds(1));
        gameRegistry.subscribe(ServiceKind.SCENE, event -> {
        });

        ClusterDirectory sceneDirectory = new ClusterDirectory(new InMemoryServiceRegistry());
        sceneDirectory.seed(center);
        ClusterRpcGateway sceneGateway = new ClusterRpcGateway(scene, sceneDirectory, topology, transport);
        RemoteServiceRegistry sceneRegistry = new RemoteServiceRegistry(scene.id(), sceneGateway, sceneDirectory);
        sceneRegistry.register(scene, Duration.ofSeconds(1));
        assertTrue(sceneRegistry.heartbeat(scene.id(), Duration.ofSeconds(1)));
        assertFalse(sceneRegistry.heartbeat(ServiceId.of(ServiceKind.SCENE, "r1", "missing"), Duration.ofSeconds(1)));
        assertEquals(List.of(scene), gameRegistry.list(ServiceKind.SCENE));

        centerStorage.expireLeases(Instant.now().plusSeconds(2));

        assertEquals(List.of(), gameRegistry.list(ServiceKind.SCENE));
        assertEquals(List.of(), gameDirectory.list(ServiceKind.SCENE));
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> topics) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                topics,
                Map.of()
        );
    }
}
