package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegistrySubscriptionLeaseReaperTest {
    @Test
    void expireOnceRecordsExpiredSubscriptions() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        LocalClusterTransport transport = new LocalClusterTransport();
        InMemoryServiceRegistry storage = new InMemoryServiceRegistry(clock);
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, Set.of(
                RegistryOperations.LIST,
                RegistryOperations.SUBSCRIBE,
                RegistryOperations.UNSUBSCRIBE,
                RegistryOperations.REPLAY
        ));
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001);
        storage.register(center);
        ClusterDirectory directory = new ClusterDirectory(storage);
        for (ServiceKind kind : ServiceKind.values()) {
            directory.watch(kind);
        }
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(
                center,
                directory,
                ClusterTopology.defaultCrossServer(),
                transport
        );
        CenterRegistryEndpoint endpoint = new CenterRegistryEndpoint(
                center,
                storage,
                transport,
                centerGateway,
                clock,
                Duration.ofMillis(100)
        );
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try (RegistrySubscriptionLeaseReaper reaper = new RegistrySubscriptionLeaseReaper(
                endpoint,
                clock,
                Duration.ofSeconds(1),
                executor
        )) {
            ClusterDirectory gameDirectory = new ClusterDirectory(new InMemoryServiceRegistry());
            gameDirectory.seed(center);
            ClusterRpcGateway gameGateway = new ClusterRpcGateway(
                    game,
                    gameDirectory,
                    ClusterTopology.defaultCrossServer(),
                    transport
            );
            RemoteServiceRegistry gameRegistry = new RemoteServiceRegistry(
                    game.id(),
                    gameGateway,
                    gameDirectory,
                    Duration.ofMillis(100)
            );
            gameRegistry.subscribe(ServiceKind.SCENE, event -> {
            });

            clock.advance(Duration.ofMillis(101));

            assertEquals(1, reaper.expireOnce());
            assertEquals(1, reaper.expiredSubscriptions());
            assertEquals(1, endpoint.stats().expiredSubscriptions());
        } finally {
            executor.shutdownNow();
        }
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port) {
        return descriptor(kind, node, port, Set.of());
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> topics) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                topics,
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
            return ZoneOffset.UTC;
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
