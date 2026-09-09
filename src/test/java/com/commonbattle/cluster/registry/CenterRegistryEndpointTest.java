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
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.network.ClusterMessageHandler;
import com.commonbattle.cluster.network.ClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.cross.SceneOperations;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
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
                RegistryOperations.SUBSCRIBE,
                RegistryOperations.UNSUBSCRIBE,
                RegistryOperations.REPLAY
        ));
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
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
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        InMemoryServiceRegistry centerStorage = new InMemoryServiceRegistry(clock);
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, Set.of(
                RegistryOperations.REGISTER,
                RegistryOperations.HEARTBEAT,
                RegistryOperations.UNREGISTER,
                RegistryOperations.LIST,
                RegistryOperations.SUBSCRIBE,
                RegistryOperations.UNSUBSCRIBE,
                RegistryOperations.REPLAY
        ));
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
        centerStorage.register(center);
        ClusterDirectory centerDirectory = new ClusterDirectory(centerStorage);
        for (ServiceKind kind : ServiceKind.values()) {
            centerDirectory.watch(kind);
        }
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, centerDirectory, topology, transport);
        new CenterRegistryEndpoint(center, centerStorage, transport, centerGateway, clock, Duration.ofSeconds(15));

        ClusterDirectory gameDirectory = new ClusterDirectory(new InMemoryServiceRegistry());
        gameDirectory.seed(center);
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        RemoteServiceRegistry gameRegistry = new RemoteServiceRegistry(game.id(), gameGateway, gameDirectory);
        gameRegistry.register(game, Duration.ofSeconds(10));
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

        clock.advance(Duration.ofSeconds(2));
        assertEquals(1, centerStorage.expireLeases(clock.instant()));

        assertEquals(List.of(), gameRegistry.list(ServiceKind.SCENE));
        assertEquals(List.of(), gameDirectory.list(ServiceKind.SCENE));
    }

    @Test
    void remoteRegistryRecoverSubscriptionsFallsBackToSnapshotWhenHistoryCompacted() {
        DroppingRegistryEventTransport transport = new DroppingRegistryEventTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        InMemoryServiceRegistry centerStorage = new InMemoryServiceRegistry(java.time.Clock.systemUTC(), 1);
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, Set.of(
                RegistryOperations.REGISTER,
                RegistryOperations.HEARTBEAT,
                RegistryOperations.UNREGISTER,
                RegistryOperations.LIST,
                RegistryOperations.SUBSCRIBE,
                RegistryOperations.UNSUBSCRIBE,
                RegistryOperations.REPLAY
        ));
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
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

        transport.dropRegistryEventsTo(game.id(), true);
        centerStorage.register(scene);
        centerStorage.register(descriptor(ServiceKind.GAME, "game-2", 9003, Set.of("game.resume")));
        assertEquals(List.of(), gameRegistry.list(ServiceKind.SCENE));

        transport.dropRegistryEventsTo(game.id(), false);
        gameRegistry.recoverSubscriptions();

        assertEquals(List.of(scene), gameRegistry.list(ServiceKind.SCENE));
        assertEquals(List.of(scene), gameDirectory.list(ServiceKind.SCENE));
        assertEquals(1, centerStorage.stats().compactedReplayRequests());
    }

    @Test
    void remoteRegistryUnsubscribeStopsFutureRegistryEvents() throws Exception {
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        InMemoryServiceRegistry centerStorage = new InMemoryServiceRegistry();
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, centerTopics());
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
        centerStorage.register(center);
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, watchedDirectory(centerStorage), topology, transport);
        CenterRegistryEndpoint endpoint = new CenterRegistryEndpoint(center, centerStorage, transport, centerGateway);
        RemoteServiceRegistry gameRegistry = remoteRegistry(game, center, topology, transport);
        RemoteServiceRegistry sceneRegistry = remoteRegistry(scene, center, topology, transport);
        gameRegistry.register(game);
        List<RegistryEvent> events = new java.util.ArrayList<>();

        AutoCloseable subscription = gameRegistry.subscribe(ServiceKind.SCENE, events::add);
        assertEquals(1, endpoint.stats().references());

        subscription.close();
        sceneRegistry.register(scene);

        assertEquals(0, endpoint.stats().references());
        assertEquals(1, endpoint.stats().unsubscribeRequests());
        assertEquals(List.of(), gameRegistry.list(ServiceKind.SCENE));
        assertEquals(List.of(), events);
    }

    @Test
    void remoteRegistryKeepsSingleCenterSubscriptionForMultipleLocalSubscribers() throws Exception {
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        InMemoryServiceRegistry centerStorage = new InMemoryServiceRegistry();
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, centerTopics());
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
        ServiceDescriptor scene2 = descriptor(ServiceKind.SCENE, "scene-2", 9003, Set.of(SceneOperations.ENTER));
        centerStorage.register(center);
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, watchedDirectory(centerStorage), topology, transport);
        CenterRegistryEndpoint endpoint = new CenterRegistryEndpoint(center, centerStorage, transport, centerGateway);
        RemoteServiceRegistry gameRegistry = remoteRegistry(game, center, topology, transport);
        RemoteServiceRegistry sceneRegistry = remoteRegistry(scene, center, topology, transport);
        gameRegistry.register(game);
        List<RegistryEvent> firstEvents = new java.util.ArrayList<>();
        List<RegistryEvent> secondEvents = new java.util.ArrayList<>();

        AutoCloseable first = gameRegistry.subscribe(ServiceKind.SCENE, firstEvents::add);
        AutoCloseable second = gameRegistry.subscribe(ServiceKind.SCENE, secondEvents::add);
        first.close();
        sceneRegistry.register(scene);
        second.close();
        sceneRegistry.register(scene2);

        assertEquals(1, endpoint.stats().subscribeRequests());
        assertEquals(1, endpoint.stats().unsubscribeRequests());
        assertEquals(0, endpoint.stats().references());
        assertEquals(List.of(), firstEvents);
        assertEquals(1, secondEvents.size());
        assertEquals(List.of(scene), gameRegistry.list(ServiceKind.SCENE));
    }

    @Test
    void unregisteredSubscriberIsRemovedFromCenterSubscriptionTable() {
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        InMemoryServiceRegistry centerStorage = new InMemoryServiceRegistry();
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, centerTopics());
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        centerStorage.register(center);
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, watchedDirectory(centerStorage), topology, transport);
        CenterRegistryEndpoint endpoint = new CenterRegistryEndpoint(center, centerStorage, transport, centerGateway);
        RemoteServiceRegistry gameRegistry = remoteRegistry(game, center, topology, transport);
        gameRegistry.register(game);
        gameRegistry.subscribe(ServiceKind.SCENE, event -> {
        });

        gameRegistry.unregister(game.id());

        assertEquals(0, endpoint.stats().references());
        assertEquals(0, endpoint.stats().subscribers());
        assertEquals(1, endpoint.stats().cleanedSubscribers());
    }

    @Test
    void expiredSubscriptionStopsFutureRegistryEvents() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        InMemoryServiceRegistry centerStorage = new InMemoryServiceRegistry(clock);
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, centerTopics());
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
        centerStorage.register(center);
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, watchedDirectory(centerStorage), topology, transport);
        CenterRegistryEndpoint endpoint = new CenterRegistryEndpoint(
                center,
                centerStorage,
                transport,
                centerGateway,
                clock,
                Duration.ofMillis(100)
        );
        RemoteServiceRegistry gameRegistry = remoteRegistry(game, center, topology, transport, Duration.ofMillis(100));
        RemoteServiceRegistry sceneRegistry = remoteRegistry(scene, center, topology, transport, Duration.ofMillis(100));
        gameRegistry.register(game);
        gameRegistry.subscribe(ServiceKind.SCENE, event -> {
        });

        clock.advance(Duration.ofMillis(101));
        assertEquals(1, endpoint.expireSubscriptions(clock.instant()));
        sceneRegistry.register(scene);

        assertEquals(0, endpoint.stats().references());
        assertEquals(1, endpoint.stats().expiredSubscriptions());
        assertEquals(List.of(), gameRegistry.list(ServiceKind.SCENE));
    }

    @Test
    void recoveringRemoteSubscriptionRefreshesCenterLease() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        InMemoryServiceRegistry centerStorage = new InMemoryServiceRegistry(clock);
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, centerTopics());
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        centerStorage.register(center);
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, watchedDirectory(centerStorage), topology, transport);
        CenterRegistryEndpoint endpoint = new CenterRegistryEndpoint(
                center,
                centerStorage,
                transport,
                centerGateway,
                clock,
                Duration.ofMillis(100)
        );
        RemoteServiceRegistry gameRegistry = remoteRegistry(game, center, topology, transport, Duration.ofMillis(100));
        gameRegistry.register(game);
        gameRegistry.subscribe(ServiceKind.SCENE, event -> {
        });

        clock.advance(Duration.ofMillis(80));
        assertEquals(1, gameRegistry.recoverSubscriptions());
        clock.advance(Duration.ofMillis(80));
        assertEquals(0, endpoint.expireSubscriptions(clock.instant()));
        clock.advance(Duration.ofMillis(21));

        assertEquals(1, endpoint.expireSubscriptions(clock.instant()));
        assertEquals(1, endpoint.stats().expiredSubscriptions());
        assertEquals(2, endpoint.stats().subscribeRequests());
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> topics) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                topics,
                Map.of()
        );
    }

    private static Set<String> centerTopics() {
        return Set.of(
                RegistryOperations.REGISTER,
                RegistryOperations.HEARTBEAT,
                RegistryOperations.UNREGISTER,
                RegistryOperations.LIST,
                RegistryOperations.SUBSCRIBE,
                RegistryOperations.UNSUBSCRIBE,
                RegistryOperations.REPLAY
        );
    }

    private static ClusterDirectory watchedDirectory(InMemoryServiceRegistry registry) {
        ClusterDirectory directory = new ClusterDirectory(registry);
        for (ServiceKind kind : ServiceKind.values()) {
            directory.watch(kind);
        }
        return directory;
    }

    private static RemoteServiceRegistry remoteRegistry(
            ServiceDescriptor local,
            ServiceDescriptor center,
            ClusterTopology topology,
            ClusterTransport transport
    ) {
        ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
        directory.seed(center);
        ClusterRpcGateway gateway = new ClusterRpcGateway(local, directory, topology, transport);
        return new RemoteServiceRegistry(local.id(), gateway, directory);
    }

    private static RemoteServiceRegistry remoteRegistry(
            ServiceDescriptor local,
            ServiceDescriptor center,
            ClusterTopology topology,
            ClusterTransport transport,
            Duration subscriptionLeaseTtl
    ) {
        ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
        directory.seed(center);
        ClusterRpcGateway gateway = new ClusterRpcGateway(local, directory, topology, transport);
        return new RemoteServiceRegistry(local.id(), gateway, directory, subscriptionLeaseTtl);
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
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class DroppingRegistryEventTransport implements ClusterTransport {
        private final LocalClusterTransport delegate = new LocalClusterTransport();
        private final Set<ServiceId> droppedRegistryEventTargets = new HashSet<>();

        @Override
        public void bind(ServiceDescriptor local, ClusterMessageHandler handler) {
            delegate.bind(local, handler);
        }

        @Override
        public void send(ServiceId nextHop, ClusterEnvelope envelope) {
            if (RegistryOperations.EVENT.equals(envelope.operation()) && droppedRegistryEventTargets.contains(nextHop)) {
                return;
            }
            delegate.send(nextHop, envelope);
        }

        private void dropRegistryEventsTo(ServiceId target, boolean dropped) {
            if (dropped) {
                droppedRegistryEventTargets.add(target);
            } else {
                droppedRegistryEventTargets.remove(target);
            }
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
