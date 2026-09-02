package com.commonbattle.observability;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceRegistry;
import com.commonbattle.cluster.event.ClusterEventCenter;
import com.commonbattle.cluster.event.ClusterEventOperations;
import com.commonbattle.cluster.event.ClusterEventSubscriptionManager;
import com.commonbattle.cluster.event.ClusterVersionedEventBus;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.registry.RegistryLeaseReaper;
import com.commonbattle.cluster.registry.RegistryLeaseRenewer;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcGovernanceConfig;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.config.GameConfigChangedEvent;
import com.commonbattle.game.config.GameConfigApplyResult;
import com.commonbattle.game.config.GameConfigAutoRecovery;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.session.InMemoryPlayerCommandAuditLog;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandAuditOutcome;
import com.commonbattle.game.session.PlayerCommandAuditRecord;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerCommandStatus;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.LocalProfileCache;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import com.commonbattle.game.profile.ProfileInterestStats;
import com.commonbattle.game.profile.ProfileInterestView;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeHealthProbeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void snapshotContainsActorAgentOutboxAndClusterStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        InMemoryAgentDirectory agentDirectory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, agentDirectory, CLOCK);
        lifecycles.activate(AgentIdentity.player(10001L), "player-10001");
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(CLOCK);
        outbox.append(profileEvent());
        outbox.markAttemptFailed(1);
        ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
        directory.seed(descriptor(ServiceKind.CENTER, "center-1"));
        directory.seed(descriptor(ServiceKind.GAME, "game-1"));
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                lifecycles,
                outbox,
                directory,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.agents().count(AgentLifecycleState.ACTIVE));
        assertEquals(1, snapshot.outbox().pendingEvents());
        assertEquals(1, snapshot.outbox().failedAttempts());
        assertEquals(1, snapshot.cluster().count(ServiceKind.CENTER));
        assertEquals(1, snapshot.cluster().count(ServiceKind.GAME));
    }

    @Test
    void closedActorSystemReportsDown() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                RuntimeHealthPolicy.defaults()
        );

        actors.close();

        assertEquals(RuntimeHealthStatus.DOWN, probe.snapshot().status());
    }

    @Test
    void snapshotAggregatesRpcGatewayStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1");
        ServiceDescriptor scene = new ServiceDescriptor(
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                new ServiceEndpoint("127.0.0.1", 9002),
                Set.of("scene.hold"),
                Map.of()
        );
        registry.register(game);
        registry.register(scene);
        ClusterDirectory gameDirectory = watchedDirectory(registry);
        ClusterDirectory sceneDirectory = watchedDirectory(registry);
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        RpcGovernanceConfig rpcConfig = new RpcGovernanceConfig(
                Duration.ofSeconds(1),
                10,
                Duration.ofMillis(200),
                100
        );
        ClusterRpcGateway sceneGateway = new ClusterRpcGateway(scene, sceneDirectory, topology, transport);
        sceneGateway.handle("scene.hold", (request, responder) -> {
        });
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport, true, rpcConfig);
        gameGateway.call(new com.commonbattle.actor.rpc.RpcRequest<>(
                ServiceKind.SCENE.name(),
                "scene.hold",
                "hello",
                String.class
        ), new NoopCallback());
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(game.id(), actors, new InMemoryAgentDirectory(), CLOCK),
                new InMemoryVersionedEventOutbox(CLOCK),
                gameDirectory,
                java.util.List.of(gameGateway, sceneGateway),
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(1, snapshot.rpc().pendingRequests());
        assertEquals(1, snapshot.rpc().sentRequests());
    }

    @Test
    void snapshotAggregatesPlayerCommandStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        sessions.bind(10001L, "session-1");
        PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                sessions,
                new PlayerCommandSequencer(),
                new com.commonbattle.actor.backpressure.AdmissionControlledAgentRouter(
                        (target, operation) -> com.commonbattle.actor.backpressure.AdmissionDecision.reject(
                                "rate_limited",
                                Duration.ofMillis(100)
                        ),
                        new com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter(
                                new AgentLifecycleManager(
                                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                                        actors,
                                        new InMemoryAgentDirectory(),
                                        CLOCK
                                ),
                                new com.commonbattle.actor.message.DefaultAgentMessagePort(actors, new NoopRpcGateway())
                        )
                )
        );
        dispatcher.handle("bag.use", (context, command) -> {
        });
        dispatcher.dispatch(new PlayerCommand(10001L, "session-1", 1, 1, "bag.use", ""));
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                java.util.List.of(),
                java.util.List.of(dispatcher),
                RuntimeHealthPolicy.defaults()
        );

        assertEquals(1, probe.snapshot().commands().count(PlayerCommandStatus.RATE_LIMITED));
    }

    @Test
    void missingConfigCacheReportsRuntimeDown() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        LocalGameConfigCache configCache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(configCache),
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(RuntimeHealthStatus.DOWN, snapshot.status());
        assertEquals(1, snapshot.configCaches().cacheCount());
        assertEquals(0, snapshot.configCaches().activeCaches());
    }

    @Test
    void staleConfigCacheReportsRuntimeDegradedWhenActiveConfigExists() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        LocalGameConfigCache configCache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        configCache.apply(GameConfigChangedEvent.activePublished(1, ExampleGameConfigs.basic(1, CLOCK.instant())));
        configCache.apply(GameConfigChangedEvent.activePublished(
                3,
                ExampleGameConfigs.basic(3, CLOCK.instant())
        ));
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(configCache),
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.configCaches().activeCaches());
        assertEquals(1, snapshot.configCaches().staleCaches());
    }

    @Test
    void snapshotAggregatesConfigAutoRecoveryInFlightStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        LocalGameConfigCache configCache = readyConfigCache();
        GameConfigAutoRecovery recovery = new GameConfigAutoRecovery(callback -> {
        });
        configCache.attachRecoveryTrigger(recovery);
        configCache.apply(GameConfigChangedEvent.activePublished(3, ExampleGameConfigs.basic(3, CLOCK.instant())));
        configCache.apply(GameConfigChangedEvent.activePublished(4, ExampleGameConfigs.basic(4, CLOCK.instant())));
        RuntimeHealthProbe probe = probeWithConfig(actors, configCache, recovery);

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(2, snapshot.configRecoveries().requested());
        assertEquals(1, snapshot.configRecoveries().skippedWhileInFlight());
        assertEquals(1, snapshot.configRecoveries().inFlight());
        assertEquals(0, snapshot.configRecoveries().succeeded());
        assertEquals(0, snapshot.configRecoveries().failed());
    }

    @Test
    void snapshotAggregatesConfigAutoRecoveryCompletedStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        LocalGameConfigCache successCache = readyConfigCache();
        GameConfigAutoRecovery successRecovery = new GameConfigAutoRecovery(callback -> callback.accept(
                successCache.applySnapshot(com.commonbattle.game.config.GameConfigSnapshot.activeOnly(
                        3,
                        ExampleGameConfigs.basic(3, CLOCK.instant())
                ))
        ));
        successCache.attachRecoveryTrigger(successRecovery);
        successCache.apply(GameConfigChangedEvent.activePublished(3, ExampleGameConfigs.basic(3, CLOCK.instant())));
        LocalGameConfigCache failedCache = readyConfigCache();
        GameConfigAutoRecovery failedRecovery = new GameConfigAutoRecovery(callback -> callback.accept(
                GameConfigApplyResult.recoveryFailed(1, "center unavailable")
        ));
        failedCache.attachRecoveryTrigger(failedRecovery);
        failedCache.apply(GameConfigChangedEvent.activePublished(3, ExampleGameConfigs.basic(3, CLOCK.instant())));
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(successCache, failedCache),
                java.util.List.of(successRecovery, failedRecovery),
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(2, snapshot.configRecoveries().requested());
        assertEquals(1, snapshot.configRecoveries().succeeded());
        assertEquals(1, snapshot.configRecoveries().failed());
        assertEquals(0, snapshot.configRecoveries().inFlight());
    }

    @Test
    void snapshotAggregatesPlayerCommandAuditStatsAndConfigVersions() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        InMemoryPlayerCommandAuditLog audit = new InMemoryPlayerCommandAuditLog();
        audit.record(auditRecord(PlayerCommandAuditOutcome.EXECUTED, PlayerCommandStatus.ACCEPTED, 1, 5));
        audit.record(auditRecord(PlayerCommandAuditOutcome.FAILED, PlayerCommandStatus.ACCEPTED, 2, 30));
        audit.record(auditRecord(PlayerCommandAuditOutcome.REJECTED, PlayerCommandStatus.RATE_LIMITED, 0, 0));
        audit.record(auditRecord(PlayerCommandAuditOutcome.ROUTED_REMOTE, PlayerCommandStatus.ROUTED_REMOTE, 0, 0));
        audit.record(auditRecord(PlayerCommandAuditOutcome.EXECUTED, PlayerCommandStatus.ACCEPTED, 2, 10));
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(audit),
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(5, snapshot.commandAudits().total());
        assertEquals(5, snapshot.commandAudits().retained());
        assertEquals(0, snapshot.commandAudits().dropped());
        assertEquals(2, snapshot.commandAudits().executed());
        assertEquals(1, snapshot.commandAudits().failed());
        assertEquals(1, snapshot.commandAudits().rejected());
        assertEquals(1, snapshot.commandAudits().routedRemote());
        assertEquals(30, snapshot.commandAudits().maxElapsedMillis());
        assertEquals(1, snapshot.commandAudits().configVersionCounts().get(1L));
        assertEquals(2, snapshot.commandAudits().configVersionCounts().get(2L));
        assertEquals(2, snapshot.commandAudits().configVersionCounts().get(0L));
    }

    @Test
    void snapshotIncludesAuditWindowDropStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        InMemoryPlayerCommandAuditLog audit = new InMemoryPlayerCommandAuditLog(2);
        audit.record(auditRecord(PlayerCommandAuditOutcome.EXECUTED, PlayerCommandStatus.ACCEPTED, 1, 5));
        audit.record(auditRecord(PlayerCommandAuditOutcome.EXECUTED, PlayerCommandStatus.ACCEPTED, 2, 10));
        audit.record(auditRecord(PlayerCommandAuditOutcome.REJECTED, PlayerCommandStatus.RATE_LIMITED, 0, 0));
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(audit),
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(2, snapshot.commandAudits().total());
        assertEquals(2, snapshot.commandAudits().retained());
        assertEquals(1, snapshot.commandAudits().dropped());
        assertEquals(1, snapshot.commandAudits().executed());
        assertEquals(1, snapshot.commandAudits().rejected());
        assertNull(snapshot.commandAudits().configVersionCounts().get(1L));
        assertEquals(1, snapshot.commandAudits().configVersionCounts().get(2L));
        assertEquals(1, snapshot.commandAudits().configVersionCounts().get(0L));
    }

    @Test
    void snapshotAggregatesRegistryLeaseStatsAndDegradesOnRenewalFailure() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1");
        InMemoryServiceRegistry healthyRegistry = new InMemoryServiceRegistry(CLOCK);
        healthyRegistry.register(game, Duration.ofSeconds(5));
        RegistryLeaseRenewer successful = new RegistryLeaseRenewer(
                healthyRegistry,
                game,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1)
        );
        RegistryLeaseRenewer reRegistered = new RegistryLeaseRenewer(
                new InMemoryServiceRegistry(CLOCK),
                game,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1)
        );
        RegistryLeaseRenewer failed = new RegistryLeaseRenewer(
                new FailingHeartbeatRegistry(),
                game,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1)
        );
        InMemoryServiceRegistry expiringRegistry = new InMemoryServiceRegistry(CLOCK);
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1");
        expiringRegistry.register(scene, Duration.ofSeconds(1));
        RegistryLeaseReaper reaper = new RegistryLeaseReaper(
                expiringRegistry,
                Clock.fixed(CLOCK.instant().plusSeconds(2), ZoneOffset.UTC),
                Duration.ofSeconds(1)
        );
        try {
            successful.renewOnce();
            reRegistered.renewOnce();
            assertThrows(IllegalStateException.class, failed::renewOnce);
            reaper.expireOnce();

            RuntimeHealthProbe probe = new RuntimeHealthProbe(
                    CLOCK,
                    actors,
                    new AgentLifecycleManager(
                            ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                            actors,
                            new InMemoryAgentDirectory(),
                            CLOCK
                    ),
                    new InMemoryVersionedEventOutbox(CLOCK),
                    new ClusterDirectory(new InMemoryServiceRegistry()),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(successful, reRegistered, failed),
                    java.util.List.of(reaper),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    RuntimeHealthPolicy.defaults()
            );

            RuntimeHealthSnapshot snapshot = probe.snapshot();

            assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
            assertEquals(3, snapshot.registryLeases().renewers());
            assertEquals(1, snapshot.registryLeases().successfulHeartbeats());
            assertEquals(1, snapshot.registryLeases().reRegistrations());
            assertEquals(1, snapshot.registryLeases().failedRenewals());
            assertEquals(1, snapshot.registryLeases().reapers());
            assertEquals(1, snapshot.registryLeases().expiredServices());
        } finally {
            successful.close();
            reRegistered.close();
            failed.close();
            reaper.close();
        }
    }

    @Test
    void snapshotAggregatesNetworkTransportStatsAndDegradesOnTransportFailure() throws IOException {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1");
        ServiceDescriptor scene = new ServiceDescriptor(
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                new ServiceEndpoint("127.0.0.1", freePort()),
                Set.of("scene.enter"),
                Map.of()
        );
        NettyClusterTransport transport = new NettyClusterTransport(
                serviceId -> scene.endpoint(),
                PayloadCodecRegistry.commonDefaults()
        );
        try {
            assertThrows(IllegalStateException.class, () -> transport.send(scene.id(), new ClusterEnvelope(
                    1,
                    game.id(),
                    scene.id(),
                    "scene.enter",
                    "payload"
            )));
            RuntimeHealthProbe probe = new RuntimeHealthProbe(
                    CLOCK,
                    actors,
                    new AgentLifecycleManager(game.id(), actors, new InMemoryAgentDirectory(), CLOCK),
                    new InMemoryVersionedEventOutbox(CLOCK),
                    new ClusterDirectory(new InMemoryServiceRegistry()),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(transport),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    RuntimeHealthPolicy.defaults()
            );

            RuntimeHealthSnapshot snapshot = probe.snapshot();

            assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
            assertEquals(1, snapshot.networkTransports().transportCount());
            assertEquals(1, snapshot.networkTransports().connectionAttempts());
            assertEquals(1, snapshot.networkTransports().connectionFailures());
            assertEquals(0, snapshot.networkTransports().activeConnections());
        } finally {
            transport.close();
        }
    }

    @Test
    void snapshotAggregatesEventSubscriptionRecoveryStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        EventFixture fixture = eventFixture(1);
        LocalProfileCache cache = new LocalProfileCache();
        ClusterEventSubscriptionManager manager = new ClusterEventSubscriptionManager(fixture.sceneEvents());
        fixture.gameEvents().publish(profileEvent(1));
        fixture.gameEvents().publish(profileEvent(2));
        manager.register(
                ProfileChangedEvent.TOPIC,
                event -> cache.apply((ProfileChangedEvent) event),
                () -> Map.of("profile:10001", 0L)
        );
        manager.start();
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(manager),
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.eventSubscriptions().managerCount());
        assertEquals(1, snapshot.eventSubscriptions().replayUnavailableOwners());
        assertTrue(json.contains("\"eventSubscriptions\""));
        assertTrue(metrics.contains("commonbattle_event_replay_unavailable_owners_total 1"));
    }

    @Test
    void snapshotAggregatesEventCenterHistoryStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        EventFixture fixture = eventFixture(1);
        fixture.gameEvents().publish(profileEvent(1));
        fixture.gameEvents().publish(profileEvent(2));
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(fixture.center()),
                java.util.List.of(),
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(1, snapshot.eventCenters().centerCount());
        assertEquals(1, snapshot.eventCenters().topicCount());
        assertEquals(1, snapshot.eventCenters().retainedEvents());
        assertEquals(2, snapshot.eventCenters().publishedEvents());
        assertEquals(1, snapshot.eventCenters().droppedEvents());
        assertEquals(1, snapshot.eventCenters().topics().get(ProfileChangedEvent.TOPIC).retainedEvents());
        assertTrue(json.contains("\"profile.changed\""));
        assertTrue(metrics.contains("commonbattle_event_center_published_events_total 2"));
        assertTrue(metrics.contains("commonbattle_event_center_dropped_events_total 1"));
        assertTrue(metrics.contains("commonbattle_event_center_topic_dropped_events_total{topic=\"profile.changed\"} 1"));
    }

    @Test
    void snapshotAggregatesProfileInterestStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        ProfileInterestView interest = () -> new ProfileInterestStats(2, 3, 1, 4, 1, 2, 1);
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(interest),
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.profileInterests().subscriptionCount());
        assertEquals(2, snapshot.profileInterests().watchedOwners());
        assertTrue(json.contains("\"profileInterests\""));
        assertTrue(metrics.contains("commonbattle_profile_interest_watched_owners 2"));
        assertTrue(metrics.contains("commonbattle_profile_interest_repair_failures_total 1"));
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", 9000),
                Set.of(),
                Map.of()
        );
    }

    private static ClusterDirectory watchedDirectory(InMemoryServiceRegistry registry) {
        ClusterDirectory directory = new ClusterDirectory(registry);
        for (ServiceKind kind : ServiceKind.values()) {
            directory.watch(kind);
        }
        return directory;
    }

    private static ProfileChangedEvent profileEvent() {
        return new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.NAME),
                new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        10,
                        AppearanceSummary.defaults(),
                        AllianceBrief.none(),
                        new FriendBrief(0, 0),
                        1,
                        CLOCK.instant()
                )
        );
    }

    private static ProfileChangedEvent profileEvent(long revision) {
        return new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        10,
                        AppearanceSummary.defaults(),
                        AllianceBrief.none(),
                        new FriendBrief(0, 0),
                        revision,
                        CLOCK.instant()
                )
        );
    }

    private static EventFixture eventFixture(int historyLimit) {
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDescriptor center = new ServiceDescriptor(
                ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                new ServiceEndpoint("127.0.0.1", 9100),
                Set.of(
                        ClusterEventOperations.SUBSCRIBE,
                        ClusterEventOperations.UNSUBSCRIBE,
                        ClusterEventOperations.PUBLISH,
                        ClusterEventOperations.REPLAY
                ),
                Map.of()
        );
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1");
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1");
        registry.register(center);
        registry.register(game);
        registry.register(scene);
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, watchedDirectory(registry), topology, transport);
        ClusterEventCenter eventCenter = new ClusterEventCenter(center, transport, centerGateway, historyLimit);
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, watchedDirectory(registry), topology, transport);
        ClusterRpcGateway sceneGateway = new ClusterRpcGateway(scene, watchedDirectory(registry), topology, transport);
        return new EventFixture(
                eventCenter,
                new ClusterVersionedEventBus(game.id(), gameGateway),
                new ClusterVersionedEventBus(scene.id(), sceneGateway)
        );
    }

    private static LocalGameConfigCache readyConfigCache() {
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        cache.apply(GameConfigChangedEvent.activePublished(1, ExampleGameConfigs.basic(1, CLOCK.instant())));
        return cache;
    }

    private static RuntimeHealthProbe probeWithConfig(
            ActorSystem actors,
            LocalGameConfigCache configCache,
            GameConfigAutoRecovery recovery
    ) {
        return new RuntimeHealthProbe(
                CLOCK,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        CLOCK
                ),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(configCache),
                java.util.List.of(recovery),
                RuntimeHealthPolicy.defaults()
        );
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static PlayerCommandAuditRecord auditRecord(
            PlayerCommandAuditOutcome outcome,
            PlayerCommandStatus status,
            long configVersion,
            long elapsedMillis
    ) {
        return new PlayerCommandAuditRecord(
                10001L,
                "session-1",
                1,
                1,
                "bag.use",
                status,
                outcome,
                configVersion,
                Duration.ofMillis(elapsedMillis),
                CLOCK.instant(),
                ""
        );
    }

    private static final class InlineExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class NoopCallback implements com.commonbattle.actor.rpc.RpcCallback<String> {
        @Override
        public void success(String response) {
        }

        @Override
        public void failure(Throwable error) {
        }
    }

    private static final class NoopRpcGateway implements com.commonbattle.actor.rpc.RpcGateway {
        @Override
        public <T> void call(com.commonbattle.actor.rpc.RpcRequest<T> request, com.commonbattle.actor.rpc.RpcCallback<T> callback) {
        }
    }

    private static final class FailingHeartbeatRegistry implements ServiceRegistry {
        @Override
        public void register(ServiceDescriptor service) {
        }

        @Override
        public boolean heartbeat(ServiceId serviceId, Duration leaseTtl) {
            throw new IllegalStateException("center unavailable");
        }

        @Override
        public void unregister(ServiceId serviceId) {
        }

        @Override
        public java.util.List<ServiceDescriptor> list(ServiceKind kind) {
            return java.util.List.of();
        }

        @Override
        public AutoCloseable subscribe(ServiceKind kind, com.commonbattle.cluster.RegistrySubscriber subscriber) {
            return () -> {
            };
        }
    }

    private record EventFixture(
            ClusterEventCenter center,
            ClusterVersionedEventBus gameEvents,
            ClusterVersionedEventBus sceneEvents
    ) {
    }
}
