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
import com.commonbattle.game.bag.BagChangeAuditRecord;
import com.commonbattle.game.bag.InMemoryBagChangeAuditLog;
import com.commonbattle.game.event.EventGatedCommandAuditRecord;
import com.commonbattle.game.event.EventGatedCommandBoundary;
import com.commonbattle.game.event.InMemoryEventGatedCommandAuditLog;
import com.commonbattle.game.config.GameConfigAutoRecovery;
import com.commonbattle.game.config.GameConfigChangedEvent;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.event.OwnerEventRepairDispatcher;
import com.commonbattle.game.event.OwnerEventRepairScheduler;
import com.commonbattle.game.event.VersionedEventOutbox;
import com.commonbattle.game.player.PlayerBusinessOperations;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.session.InMemoryPlayerCommandAuditLog;
import com.commonbattle.game.session.PlayerCommandAuditOutcome;
import com.commonbattle.game.session.PlayerCommandAuditRecord;
import com.commonbattle.game.session.PlayerCommandStatus;
import com.commonbattle.observability.OpsHttpServer;
import com.commonbattle.runtime.DrainableComponent;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        InMemoryBagChangeAuditLog bagAudits = new InMemoryBagChangeAuditLog();
        bagAudits.record(new BagChangeAuditRecord(10001L, 2, PlayerBusinessOperations.BAG_CONSUME_ITEM,
                "exp_potion", 3, 1, CLOCK.instant()));
        runtime.observe("bagAudits", bagAudits);
        InMemoryPlayerCommandAuditLog commandAudits = new InMemoryPlayerCommandAuditLog();
        commandAudits.record(new PlayerCommandAuditRecord(
                10001L,
                "session-1",
                1,
                2,
                PlayerBusinessOperations.BAG_CONSUME_ITEM,
                PlayerCommandStatus.ACCEPTED,
                PlayerCommandAuditOutcome.EXECUTED,
                7,
                Duration.ofMillis(4),
                CLOCK.instant(),
                "SUCCESS",
                ""
        ));
        runtime.observe("commandAudits", commandAudits);
        InMemoryEventGatedCommandAuditLog eventGatedAudits = new InMemoryEventGatedCommandAuditLog();
        eventGatedAudits.record(new EventGatedCommandAuditRecord(
                "chat.direct.send.afterFriendChanged",
                "DirectChatSendAfterFriendEventRequest",
                new EventGatedCommandBoundary("friend.changed", "friend:10001", 3),
                "completed",
                "SENT",
                CLOCK.instant()
        ));
        runtime.observe("eventGatedAudits", eventGatedAudits);
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
            assertTrue(health.contains("\"bagChangeAudits\":{\"auditCount\":1,\"retained\":1"));
            HttpResult bagAudit = get(server, "/bag-change-audit?playerId=10001");
            assertEquals(200, bagAudit.statusCode());
            assertTrue(bagAudit.body().contains("\"reason\":\"bag.consumeItem\""));
            assertTrue(bagAudit.body().contains("\"itemId\":\"exp_potion\""));
            HttpResult commandAudit = get(server, "/player-command-audit?playerId=10001");
            assertEquals(200, commandAudit.statusCode());
            assertTrue(commandAudit.body().contains("\"operation\":\"bag.consumeItem\""));
            assertTrue(commandAudit.body().contains("\"configVersion\":7"));
            HttpResult eventGatedAudit = get(server, "/event-gated-command-audit?ownerKey=friend%3A10001");
            assertEquals(200, eventGatedAudit.statusCode());
            assertTrue(eventGatedAudit.body().contains("\"operation\":\"chat.direct.send.afterFriendChanged\""));
            assertTrue(eventGatedAudit.body().contains("\"result\":\"SENT\""));
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

    @Test
    void bootOpsHttpUsesConfiguredAdminTokenForMutatingOps() throws Exception {
        Properties properties = properties(freePort());
        properties.setProperty("cluster.ops.admin.token", "secret");
        properties.setProperty("cluster.ops.admin.token.header", "X-Ops-Token");
        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);
        ServiceDescriptor local = ClusterDescriptors.fromConfig(config);
        ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
        BootRuntime runtime = new BootRuntime();
        ActorSystem actors = runtime.add("actors", new ActorSystem(Runnable::run, 64));
        RecordingDrainable drainable = new RecordingDrainable();
        runtime.observe("commandIngress", drainable);
        try (OpsHttpServer server = runtime.add("opsHttp", BootOpsHttp.start(
                config,
                local,
                actors,
                directory,
                runtime.healthRegistry()
        ))) {
            HttpResult unauthDrain = post(server, "/drain", null);
            HttpResult authDrain = post(server, "/drain", "secret");

            assertEquals(401, unauthDrain.statusCode());
            assertEquals("{\"error\":\"unauthorized\"}", unauthDrain.body());
            assertEquals(200, authDrain.statusCode());
            assertTrue(drainable.isDraining());
        } finally {
            runtime.close();
        }
    }

    @Test
    void bootOpsHttpExposesAndAuditsConfiguredOwnerRepairIsolationAdmins() throws Exception {
        Properties properties = properties(freePort());
        properties.setProperty("cluster.event.repair.topic.profile.changed.owner.isolation.max.failures", "1");
        properties.setProperty("cluster.event.repair.topic.profile.changed.owner.isolation.duration.millis", "60000");
        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);
        ServiceDescriptor local = ClusterDescriptors.fromConfig(config);
        ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
        BootRuntime runtime = new BootRuntime();
        OwnerEventRepairDispatcher dispatcher = null;
        try {
            ActorSystem actors = runtime.add("actors", new ActorSystem(Runnable::run, 64));
            dispatcher = BootOwnerEventRepairs.repairDispatcher(config);
            OwnerEventInterestControl repairs = BootOwnerEventRepairs.repairControl(
                    runtime,
                    config,
                    dispatcher,
                    "profileEventRepairs",
                    ProfileChangedEvent.TOPIC,
                    new FailingRepairInterests()
            );
            OwnerEventRepairScheduler scheduler = (OwnerEventRepairScheduler) repairs;
            String ownerKey = ProfileChangedEvent.ownerKey(10001L);
            repairs.requestRepairOwner(ownerKey);
            assertThrows(IllegalStateException.class, scheduler::drainOnce);

            try (OpsHttpServer server = runtime.add("opsHttp", BootOpsHttp.start(
                    config,
                    local,
                    actors,
                    directory,
                    runtime.healthRegistry()
            ))) {
                HttpResult isolated = get(server, "/owner-repair/isolated");
                HttpResult release = post(server, "/owner-repair/release?ownerKey=profile%3A10001", null);
                HttpResult request = post(server,
                        "/owner-repair/request?target=profileEventRepairs&ownerKey=profile%3A10002&priority=9",
                        null);
                HttpResult health = get(server, "/health");

                assertEquals(200, isolated.statusCode());
                assertTrue(isolated.body().contains("\"ownerKey\":\"" + ownerKey + "\""));
                assertEquals(200, release.statusCode());
                assertEquals("{\"released\":1,\"ownerKey\":\"" + ownerKey + "\"}", release.body());
                assertEquals(200, request.statusCode());
                assertEquals("{\"requested\":1,\"target\":\"profileEventRepairs\","
                        + "\"ownerKey\":\"profile:10002\",\"priority\":9}", request.body());
                assertTrue(health.body().contains("\"ownerEventRepairSchedulers\":{\"schedulerCount\":1,"
                        + "\"pendingOwners\":2"));
                assertTrue(health.body().contains("\"ownerRepairOps\":{\"auditCount\":1,\"retainedEntries\":2,"
                        + "\"recordedEntries\":2,\"releaseOps\":1,\"releaseAllOps\":0,\"releasedOwners\":1"));
            }
        } finally {
            runtime.close();
            if (dispatcher != null) {
                dispatcher.close();
            }
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

    private static HttpResult post(OpsHttpServer server, String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("X-Ops-Token", token);
        }
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString()
        );
        return new HttpResult(response.statusCode(), response.body());
    }

    private static HttpResult get(OpsHttpServer server, String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        return new HttpResult(response.statusCode(), response.body());
    }

    private record HttpResult(int statusCode, String body) {
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

    private static final class FailingRepairInterests implements OwnerEventInterestControl {
        @Override
        public void watchOwner(String ownerKey) {
        }

        @Override
        public void unwatchOwner(String ownerKey) {
        }

        @Override
        public void requestRepairOwners(Collection<String> ownerKeys) {
            throw new IllegalStateException("repair failed");
        }
    }
}
