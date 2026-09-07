package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterNode;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.event.ClusterEventSubscriptionManager;
import com.commonbattle.cluster.event.ClusterVersionedEventBus;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.config.GameConfigAutoRecovery;
import com.commonbattle.game.config.GameConfigChangedEvent;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.event.VersionedEventOutbox;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.observability.OpsHttpServer;
import com.commonbattle.runtime.DrainableComponent;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BootOpsHttpTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void bootOpsHttpIncludesRuntimeComponentsPassedByServerMain() throws Exception {
        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties(freePort()));
        ServiceDescriptor local = ClusterDescriptors.fromConfig(config);
        ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
        BootRuntime runtime = new BootRuntime();
        ActorSystem actors = runtime.add("actors", new ActorSystem(Runnable::run, 64));
        NettyClusterTransport transport = runtime.add("transport", new NettyClusterTransport(
                new DirectoryEndpointView(directory, local, ClusterDescriptors.center(config)),
                PayloadCodecRegistry.commonDefaults()
        ));
        ClusterRpcGateway gateway = runtime.add("gateway", new ClusterRpcGateway(
                local,
                directory,
                ClusterTopology.defaultCrossServer(),
                transport,
                false
        ));
        LocalGameConfigCache configCache = runtime.add("configCache",
                new LocalGameConfigCache(new GameConfigValidator(), CLOCK));
        configCache.apply(GameConfigChangedEvent.activePublished(1, ExampleGameConfigs.basic(1, CLOCK.instant())));
        GameConfigAutoRecovery configRecovery = new GameConfigAutoRecovery(callback -> {
        });
        runtime.observe("configRecovery", configRecovery);
        ClusterEventSubscriptionManager eventSubscriptions = runtime.add(
                "eventSubscriptions",
                new ClusterEventSubscriptionManager(new ClusterVersionedEventBus(local.id(), gateway))
        );
        eventSubscriptions.register(
                GameConfigChangedEvent.TOPIC,
                configCache,
                () -> Map.of(GameConfigChangedEvent.OWNER_KEY, configCache.appliedEventRevision())
        );
        ClusterNode node = runtime.add("clusterNode", new ClusterNode(new InMemoryServiceRegistry(), local, directory));
        VersionedEventOutbox outbox = BootEventOutbox.configure(runtime, config, CLOCK);
        outbox.append(new PlayerDomainVersionedEvent(
                10001L,
                BattleStageClearedEvent.TYPE,
                "forest-1",
                1,
                1,
                CLOCK.instant()
        ));
        outbox.markAttemptFailed(1);
        RecordingDrainable drainable = new RecordingDrainable();
        runtime.observe("commandIngress", drainable);
        try (OpsHttpServer server = runtime.add("opsHttp", BootOpsHttp.start(
                config,
                local,
                actors,
                directory,
                runtime.healthRegistry()
        ))) {
            String health = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            ).body();

            assertTrue(health.contains("\"configCaches\":{\"cacheCount\":1,\"activeCaches\":1"));
            assertTrue(health.contains("\"eventSubscriptions\":{\"managerCount\":1,\"registered\":1"));
            assertTrue(health.contains("\"outbox\":{\"pendingEvents\":1,\"failedAttempts\":1"));
            HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/drain"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertTrue(drainable.isDraining());
        } finally {
            runtime.close();
        }
    }

    private static Properties properties(int opsPort) {
        Properties properties = new Properties();
        properties.setProperty("cluster.kind", "GAME");
        properties.setProperty("cluster.region", "r1");
        properties.setProperty("cluster.node", "game-1");
        properties.setProperty("cluster.host", "127.0.0.1");
        properties.setProperty("cluster.port", "9001");
        properties.setProperty("cluster.center.host", "127.0.0.1");
        properties.setProperty("cluster.center.port", "9000");
        properties.setProperty("cluster.actor.workers", "4");
        properties.setProperty("cluster.ops.host", "127.0.0.1");
        properties.setProperty("cluster.ops.port", String.valueOf(opsPort));
        properties.setProperty("cluster.drain.propagation.delay.millis", "0");
        return properties;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class RecordingDrainable implements DrainableComponent {
        private final AtomicBoolean draining = new AtomicBoolean();

        @Override
        public void beginDrain() {
            draining.set(true);
        }

        @Override
        public void resumeAccepting() {
            draining.set(false);
        }

        @Override
        public boolean isDraining() {
            return draining.get();
        }
    }
}
