package com.commonbattle.observability;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;
import com.commonbattle.actor.agent.migration.AgentMigrationAcceptResponse;
import com.commonbattle.actor.agent.migration.AgentMigrationPolicy;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoveryScheduler;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoverySchedulerStats;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoveryService;
import com.commonbattle.actor.agent.migration.AgentMigrationSnapshot;
import com.commonbattle.actor.agent.migration.AgentMigrationTask;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStore;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStoreStats;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskRetentionService;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskRetentionStats;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStatus;
import com.commonbattle.actor.agent.migration.InMemoryAgentMigrationTaskStore;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.ActorRpcClient;
import com.commonbattle.actor.rpc.ActorRpcHandler;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.RegistryEvent;
import com.commonbattle.cluster.RegistryEventType;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceMetadata;
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
import com.commonbattle.cluster.registry.RemoteRegistryRecoveryStats;
import com.commonbattle.cluster.registry.RemoteRegistryRecoveryView;
import com.commonbattle.cluster.registry.RegistrySubscriptionStats;
import com.commonbattle.cluster.registry.RegistrySubscriptionView;
import com.commonbattle.cluster.registry.ServiceDescriptorPublisher;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.OptionedRpcGateway;
import com.commonbattle.cluster.rpc.RoutedRpcGateway;
import com.commonbattle.cluster.rpc.RpcCallOptions;
import com.commonbattle.cluster.rpc.RpcCircuitBreakerConfig;
import com.commonbattle.cluster.rpc.RpcGovernanceConfig;
import com.commonbattle.cluster.rpc.ResilientRpcGateway;
import com.commonbattle.cluster.rpc.RpcRetryPolicy;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.game.event.ActorMailboxEventSubscriber;
import com.commonbattle.game.event.OwnerActorEventSubscriptionStats;
import com.commonbattle.game.event.OwnerActorEventSubscriptionView;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.game.config.GameConfigChangedEvent;
import com.commonbattle.game.config.GameConfigApplyResult;
import com.commonbattle.game.config.GameConfigAutoRecovery;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.player.ActivityProgressCommand;
import com.commonbattle.game.player.AsyncShopPurchaseStats;
import com.commonbattle.game.player.AsyncShopPurchaseView;
import com.commonbattle.game.player.PlayerBusinessOperations;
import com.commonbattle.game.player.PlayerBusinessResponseHub;
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
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.profile.ProfileReadMode;
import com.commonbattle.game.profile.ProfileRuntime;
import com.commonbattle.game.scene.SceneRuntimeStats;
import com.commonbattle.game.scene.SceneRuntimeView;
import com.commonbattle.game.shop.ShopRuntimeStats;
import com.commonbattle.game.shop.ShopRuntimeView;
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
        directory.seed(withRouteMetadata(descriptor(ServiceKind.CENTER, "center-1"), "stable", "default"));
        directory.seed(ServiceMetadata.withDraining(
                withRouteMetadata(descriptor(ServiceKind.GAME, "game-1"), "gray", "canary-1"),
                true
        ));
        ServiceDescriptor scene = withRouteMetadata(descriptor(ServiceKind.SCENE, "scene-1"), "stable", "default");
        directory.accept(new RegistryEvent(RegistryEventType.REGISTERED, scene, 4));
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
        assertEquals(1, snapshot.cluster().count(ServiceKind.SCENE));
        assertEquals(1, snapshot.cluster().draining(ServiceKind.GAME));
        assertEquals(4, snapshot.cluster().version(ServiceKind.SCENE));
        assertEquals(1, snapshot.cluster().routeTag(ServiceKind.GAME, "gray"));
        assertEquals(1, snapshot.cluster().routeTag(ServiceKind.SCENE, "stable"));
        assertEquals(1, snapshot.cluster().deploymentGroup(ServiceKind.GAME, "canary-1"));
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);
        assertTrue(json.contains("\"largestMailboxQueuedTasks\":0"));
        assertTrue(json.contains("\"queuedTasksByCategory\""));
        assertTrue(json.contains("\"agentMigrations\""));
        assertTrue(json.contains("\"agentMigrationExecutors\""));
        assertTrue(json.contains("\"agentMigrationRecoveries\""));
        assertTrue(json.contains("\"agentMigrationRecoverySchedulers\""));
        assertTrue(json.contains("\"agentMigrationTaskRetentions\""));
        assertTrue(json.contains("\"agentMigrationTaskStores\""));
        assertTrue(metrics.contains("commonbattle_actor_largest_mailbox_queued_tasks 0"));
        assertTrue(metrics.contains("commonbattle_actor_queued_tasks_by_category{category=\"DEFAULT\"} 0"));
        assertTrue(metrics.contains("commonbattle_agent_migration_initiated_total 0"));
        assertTrue(metrics.contains("commonbattle_agent_migration_executor_submitted_total 0"));
        assertTrue(metrics.contains("commonbattle_agent_migration_recovery_scans_total 0"));
        assertTrue(metrics.contains("commonbattle_agent_migration_recovery_scheduler_runs_total 0"));
        assertTrue(metrics.contains("commonbattle_agent_migration_task_retention_runs_total 0"));
        assertTrue(metrics.contains("commonbattle_agent_migration_task_stores 0"));
        assertTrue(json.contains("\"draining\":{\"CENTER\":0,\"REGION\":0,\"GAME\":1,\"CHAT\":0"));
        assertTrue(json.contains("\"versions\":{\"CENTER\":0,\"REGION\":0,\"GAME\":0,\"CHAT\":0,\"SCENE\":4"));
        assertTrue(json.contains("\"routeTags\":{\"CENTER\":{\"stable\":1},\"REGION\":{},\"GAME\":{\"gray\":1},\"CHAT\":{}"));
        assertTrue(json.contains("\"deploymentGroups\":{\"CENTER\":{\"default\":1},\"REGION\":{},\"GAME\":{\"canary-1\":1},\"CHAT\":{}"));
        assertTrue(metrics.contains("commonbattle_cluster_draining_services{kind=\"GAME\"} 1"));
        assertTrue(metrics.contains("commonbattle_cluster_directory_version{kind=\"SCENE\"} 4"));
        assertTrue(metrics.contains("commonbattle_cluster_route_tag_services{kind=\"GAME\",tag=\"gray\"} 1"));
        assertTrue(metrics.contains("commonbattle_cluster_deployment_group_services{kind=\"GAME\",group=\"canary-1\"} 1"));
    }

    @Test
    void snapshotAggregatesAgentMigrationTaskRetentionStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        InMemoryAgentMigrationTaskStore store = new InMemoryAgentMigrationTaskStore();
        store.save(migrationTask(
                "migration-retention-1",
                AgentMigrationTaskStatus.TARGET_ACCEPTED,
                CLOCK.instant().minus(Duration.ofHours(2))
        ));
        AgentMigrationTaskRetentionService retention = new AgentMigrationTaskRetentionService(
                store,
                CLOCK,
                Duration.ofHours(1)
        );
        retention.purge();
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(retention);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(new AgentMigrationTaskRetentionStats(1, 1, 0), snapshot.agentMigrationTaskRetentions());
        assertTrue(json.contains("\"agentMigrationTaskRetentions\":{\"runs\":1,\"purgedTasks\":1,\"failedRuns\":0}"));
        assertTrue(metrics.contains("commonbattle_agent_migration_task_retention_runs_total 1"));
        assertTrue(metrics.contains("commonbattle_agent_migration_task_retention_purged_tasks_total 1"));
        assertTrue(metrics.contains("commonbattle_agent_migration_task_retention_failed_runs_total 0"));
    }

    @Test
    void snapshotAggregatesRegistryHistoryStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        InMemoryServiceRegistry registryStorage = new InMemoryServiceRegistry(CLOCK, 1);
        registryStorage.register(descriptor(ServiceKind.GAME, "game-1"));
        registryStorage.register(descriptor(ServiceKind.SCENE, "scene-1"));
        registryStorage.replay(ServiceKind.GAME, 0);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(registryStorage);
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
                new ClusterDirectory(registryStorage),
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(1, snapshot.registryHistory().viewCount());
        assertEquals(2, snapshot.registryHistory().currentVersion());
        assertEquals(1, snapshot.registryHistory().minReplayVersion());
        assertEquals(1, snapshot.registryHistory().retainedEvents());
        assertEquals(1, snapshot.registryHistory().historyLimit());
        assertEquals(1, snapshot.registryHistory().compactedReplayRequests());
        assertTrue(json.contains("\"registryHistory\":{\"viewCount\":1,\"currentVersion\":2"));
        assertTrue(metrics.contains("commonbattle_registry_history_current_version 2"));
        assertTrue(metrics.contains("commonbattle_registry_history_compacted_replay_requests_total 1"));
    }

    @Test
    void snapshotAggregatesRemoteRegistryRecoveryStatsAndDegradesOnFailures() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        RemoteRegistryRecoveryView recovery = () -> new RemoteRegistryRecoveryStats(
                3,
                2,
                1,
                1,
                5,
                true
        );
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(recovery);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.remoteRegistryRecoveries().schedulerCount());
        assertEquals(3, snapshot.remoteRegistryRecoveries().runs());
        assertEquals(1, snapshot.remoteRegistryRecoveries().failedRuns());
        assertEquals(5, snapshot.remoteRegistryRecoveries().recoveredKinds());
        assertEquals(1, snapshot.remoteRegistryRecoveries().inFlight());
        assertTrue(json.contains("\"remoteRegistryRecoveries\":{\"schedulerCount\":1,\"runs\":3"));
        assertTrue(metrics.contains("commonbattle_remote_registry_recovery_failed_total 1"));
    }

    @Test
    void snapshotAggregatesRegistrySubscriptionStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        RegistrySubscriptionView subscriptions = () -> new RegistrySubscriptionStats(
                2,
                3,
                4,
                8,
                5,
                1,
                1
        );
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(subscriptions);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(1, snapshot.registrySubscriptions().viewCount());
        assertEquals(2, snapshot.registrySubscriptions().subscribedKinds());
        assertEquals(3, snapshot.registrySubscriptions().subscribers());
        assertEquals(4, snapshot.registrySubscriptions().references());
        assertEquals(8, snapshot.registrySubscriptions().subscribeRequests());
        assertEquals(5, snapshot.registrySubscriptions().unsubscribeRequests());
        assertEquals(1, snapshot.registrySubscriptions().cleanedSubscribers());
        assertEquals(1, snapshot.registrySubscriptions().expiredSubscriptions());
        assertTrue(json.contains("\"registrySubscriptions\":{\"viewCount\":1,\"subscribedKinds\":2"));
        assertTrue(metrics.contains("commonbattle_registry_subscription_references 4"));
        assertTrue(metrics.contains("commonbattle_registry_subscription_expired_total 1"));
    }

    @Test
    void snapshotAggregatesAgentMigrationTaskStoreStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        InMemoryAgentMigrationTaskStore store = new InMemoryAgentMigrationTaskStore();
        store.save(migrationTask(
                "migration-store-1",
                AgentMigrationTaskStatus.PREPARED,
                CLOCK.instant().minus(Duration.ofMinutes(5))
        ));
        store.save(migrationTask(
                "migration-store-2",
                AgentMigrationTaskStatus.MOVED,
                CLOCK.instant().minus(Duration.ofMinutes(1))
        ));
        store.claim("migration-store-2", "recovery-1", CLOCK.instant(), Duration.ofSeconds(30)).orElseThrow();
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(store);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(new AgentMigrationTaskStoreStats(1, 2, 1, 1, 0, 1, 300_000),
                snapshot.agentMigrationTaskStores());
        assertTrue(json.contains("\"agentMigrationTaskStores\":{\"stores\":1,\"totalTasks\":2,\"preparedTasks\":1,\"movedTasks\":1,\"terminalTasks\":0,\"leasedPendingTasks\":1,\"oldestPendingAgeMillis\":300000}"));
        assertTrue(metrics.contains("commonbattle_agent_migration_task_store_prepared_tasks 1"));
        assertTrue(metrics.contains("commonbattle_agent_migration_task_store_leased_pending_tasks 1"));
        assertTrue(metrics.contains("commonbattle_agent_migration_task_store_oldest_pending_age_millis 300000"));
    }

    @Test
    void snapshotAggregatesAgentMigrationRecoverySchedulerStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        AgentMigrationRecoveryScheduler scheduler = new AgentMigrationRecoveryScheduler(
                emptyRecoveryService(actors),
                ignored -> {
                },
                Duration.ofSeconds(1)
        );
        scheduler.recoverOnce();
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(scheduler);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(new AgentMigrationRecoverySchedulerStats(1, 0, 0),
                snapshot.agentMigrationRecoverySchedulers());
        assertTrue(json.contains("\"agentMigrationRecoverySchedulers\":{\"runs\":1,\"claimedTasks\":0,\"failedRuns\":0}"));
        assertTrue(metrics.contains("commonbattle_agent_migration_recovery_scheduler_runs_total 1"));
    }

    @Test
    void snapshotDegradesWhenAgentMigrationRecoverySchedulerFails() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        AgentMigrationRecoveryScheduler scheduler = new AgentMigrationRecoveryScheduler(
                failingRecoveryService(actors),
                ignored -> {
                },
                Duration.ofSeconds(1)
        );
        assertThrows(IllegalStateException.class, scheduler::recoverOnce);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(scheduler);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(new AgentMigrationRecoverySchedulerStats(1, 0, 1),
                snapshot.agentMigrationRecoverySchedulers());
    }

    @Test
    void snapshotDegradesWhenMigrationPendingTaskIsTooOld() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        InMemoryAgentMigrationTaskStore store = new InMemoryAgentMigrationTaskStore();
        store.save(migrationTask(
                "migration-store-stuck",
                AgentMigrationTaskStatus.PREPARED,
                CLOCK.instant().minus(Duration.ofMinutes(5))
        ));
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(store);
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
                registry,
                new RuntimeHealthPolicy(10_000, 0, 60_000)
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(300_000, snapshot.agentMigrationTaskStores().oldestPendingAgeMillis());
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
    void snapshotAggregatesRpcResilienceStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        ResilientRpcGateway resilient = new ResilientRpcGateway(
                new com.commonbattle.actor.rpc.RpcGateway() {
                    @Override
                    public <T> void call(com.commonbattle.actor.rpc.RpcRequest<T> request, com.commonbattle.actor.rpc.RpcCallback<T> callback) {
                        callback.failure(new IllegalStateException("down"));
                    }
                },
                RpcRetryPolicy.noRetry(),
                new RpcCircuitBreakerConfig(1, Duration.ofSeconds(5))
        );
        resilient.call(new com.commonbattle.actor.rpc.RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                "one",
                String.class
        ), new NoopCallback());
        resilient.call(new com.commonbattle.actor.rpc.RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                "two",
                String.class
        ), new NoopCallback());
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
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(resilient),
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(1, snapshot.rpcResilience().attempts());
        assertEquals(1, snapshot.rpcResilience().shortCircuited());
        assertEquals(1, snapshot.rpcResilience().openCircuits());
        assertTrue(json.contains("\"rpcResilience\""));
        assertTrue(metrics.contains("commonbattle_rpc_resilience_open_circuits 1"));
    }

    @Test
    void snapshotAggregatesRpcRoutePolicyStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        RoutedRpcGateway routed = new RoutedRpcGateway(
                new ImmediateOptionedGateway(),
                RpcCallOptions.of(Duration.ofSeconds(1)),
                (request, baseOptions) -> {
                    if (request.operation().equals(SceneOperations.ENTER)) {
                        return baseOptions.withRequiredTargetMetadata(ServiceMetadata.ROUTE_TAG, "gray");
                    }
                    return baseOptions;
                }
        );
        routed.call(new com.commonbattle.actor.rpc.RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                "one",
                String.class
        ), new NoopCallback());
        routed.call(new com.commonbattle.actor.rpc.RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.LEAVE,
                "two",
                String.class
        ), new NoopCallback());
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(routed);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(1, snapshot.rpcRoutes().viewCount());
        assertEquals(2, snapshot.rpcRoutes().calls());
        assertEquals(1, snapshot.rpcRoutes().routedCalls());
        assertEquals(1, snapshot.rpcRoutes().unroutedCalls());
        assertEquals(1, snapshot.rpcRoutes().routeTagCalls().get("gray"));
        assertTrue(json.contains("\"rpcRoutes\":{\"viewCount\":1,\"calls\":2,\"routedCalls\":1"));
        assertTrue(metrics.contains("commonbattle_rpc_route_policy_views 1"));
        assertTrue(metrics.contains("commonbattle_rpc_route_tag_calls_total{tag=\"gray\"} 1"));
    }

    @Test
    void snapshotAggregatesActorRpcTemplateStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        ActorRpcClient actorRpc = new ActorRpcClient(
                actors,
                actors.actor("player-10001"),
                new com.commonbattle.actor.rpc.RpcGateway() {
                    @Override
                    public <T> void call(com.commonbattle.actor.rpc.RpcRequest<T> request, com.commonbattle.actor.rpc.RpcCallback<T> callback) {
                        callback.failure(new IllegalStateException("remote down"));
                    }
                },
                error -> AgentDeliveryResult.remoteUnavailable("mapped_remote_down")
        );
        actorRpc.call(new com.commonbattle.actor.rpc.RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                "payload",
                String.class
        ), new ActorRpcHandler<>() {
            @Override
            public void success(com.commonbattle.actor.ActorContext context, String response) {
            }

            @Override
            public void failure(com.commonbattle.actor.ActorContext context, AgentDeliveryResult delivery, Throwable error) {
            }
        });
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
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(actorRpc),
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(1, snapshot.actorRpc().clientCount());
        assertEquals(1, snapshot.actorRpc().calls());
        assertEquals(1, snapshot.actorRpc().failedResponses());
        assertTrue(json.contains("\"actorRpc\""));
        assertTrue(metrics.contains("commonbattle_actor_rpc_failed_responses_total 1"));
        assertTrue(metrics.contains("commonbattle_actor_rpc_failed_responses_by_status_total{status=\"REMOTE_UNAVAILABLE\"} 1"));
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
        dispatcher.beginDrain();
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

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.commands().count(PlayerCommandStatus.RATE_LIMITED));
        assertEquals(0, snapshot.commands().acceptingDispatchers());
        assertEquals(1, snapshot.commands().drainingDispatchers());
        assertTrue(json.contains("\"drainingDispatchers\":1"));
        assertTrue(metrics.contains("commonbattle_player_command_draining_dispatchers 1"));
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
                Set.of(SceneOperations.ENTER),
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
                    SceneOperations.ENTER,
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
    void snapshotAggregatesServiceDescriptorPublisherStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        ServiceDescriptorPublisher publisher = new ServiceDescriptorPublisher(
                new FailingRegisterRegistry(),
                () -> descriptor(ServiceKind.SCENE, "scene-1"),
                Duration.ofSeconds(5),
                Duration.ofHours(1)
        );
        try {
            assertThrows(IllegalStateException.class, publisher::publishOnce);
            publisher.beginDrain();
            registry.register(publisher);

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
                    registry,
                    RuntimeHealthPolicy.defaults()
            );

            RuntimeHealthSnapshot snapshot = probe.snapshot();
            String json = RuntimeHealthJsonFormatter.format(snapshot);
            String metrics = RuntimeMetricsFormatter.format(snapshot);

            assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
            assertEquals(1, snapshot.serviceDescriptorPublishers().publisherCount());
            assertEquals(1, snapshot.serviceDescriptorPublishers().drainingPublishers());
            assertEquals(1, snapshot.serviceDescriptorPublishers().failed());
            assertTrue(json.contains("\"serviceDescriptorPublishers\""));
            assertTrue(metrics.contains("commonbattle_service_descriptor_publish_failed_total 1"));
        } finally {
            publisher.close();
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
        assertEquals(0, snapshot.eventCenters().deliveryFailures());
        assertEquals(0, snapshot.eventCenters().expiredSubscriptions());
        assertEquals(1, snapshot.eventCenters().topics().get(ProfileChangedEvent.TOPIC).retainedEvents());
        assertTrue(json.contains("\"profile.changed\""));
        assertTrue(metrics.contains("commonbattle_event_center_published_events_total 2"));
        assertTrue(metrics.contains("commonbattle_event_center_dropped_events_total 1"));
        assertTrue(metrics.contains("commonbattle_event_center_delivery_failures_total 0"));
        assertTrue(metrics.contains("commonbattle_event_center_expired_subscriptions_total 0"));
        assertTrue(metrics.contains("commonbattle_event_center_topic_dropped_events_total{topic=\"profile.changed\"} 1"));
    }

    @Test
    void snapshotAggregatesProfileInterestStats() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        ProfileInterestView interest = () -> new ProfileInterestStats(2, 5, 3, 1, 4, 1, 2, 1);
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
        assertEquals(5, snapshot.profileInterests().watchReferences());
        assertTrue(json.contains("\"profileInterests\""));
        assertTrue(metrics.contains("commonbattle_profile_interest_watched_owners 2"));
        assertTrue(metrics.contains("commonbattle_profile_interest_watch_references 5"));
        assertTrue(metrics.contains("commonbattle_profile_interest_repair_failures_total 1"));
    }

    @Test
    void snapshotAggregatesProfileRuntimeStatsAndDegradesOnLocalFallback() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        LocalProfileCache cache = new LocalProfileCache();
        ProfileRuntime profiles = new ProfileRuntime(cache, ProfileInterestControl.noop(), playerId -> java.util.Optional.empty());
        profiles.read(10001L, ProfileReadMode.LOCAL_FAST);
        cache.apply(profileEvent(1));
        profiles.read(10001L, ProfileReadMode.LOCAL_FAST);
        cache.apply(profileEvent(3));
        profiles.read(10001L, ProfileReadMode.REFRESH_IF_STALE);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(profiles);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.profileRuntimes().runtimeCount());
        assertEquals(3, snapshot.profileRuntimes().readRequests());
        assertEquals(1, snapshot.profileRuntimes().localHits());
        assertEquals(1, snapshot.profileRuntimes().localMisses());
        assertEquals(1, snapshot.profileRuntimes().localFallbacks());
        assertTrue(json.contains("\"profileRuntimes\":{\"runtimeCount\":1,\"readRequests\":3"));
        assertTrue(metrics.contains("commonbattle_profile_runtime_read_requests_total 3"));
        assertTrue(metrics.contains("commonbattle_profile_runtime_local_fallbacks_total 1"));
    }

    @Test
    void snapshotDegradesWhenProfileRemoteSnapshotIsOlderThanRequiredRevision() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        LocalProfileCache cache = new LocalProfileCache();
        ProfileRuntime profiles = new ProfileRuntime(cache, ProfileInterestControl.noop(),
                playerId -> java.util.Optional.of(profileEvent(2).snapshot()));
        profiles.readAtLeast(10001L, 3);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(profiles);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.profileRuntimes().remoteStale());
        assertTrue(json.contains("\"remoteStale\":1"));
        assertTrue(metrics.contains("commonbattle_profile_runtime_remote_stale_total 1"));
    }

    @Test
    void snapshotAggregatesActorEventSubscriberStatsAndDegradesOnHandlerFailure() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        ActorMailboxEventSubscriber subscriber = new ActorMailboxEventSubscriber(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-events"),
                (context, event) -> {
                    throw new IllegalStateException("event handler failed");
                }
        );
        registry.register(subscriber);
        subscriber.onEvent(profileEvent(1));
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.actorEventSubscribers().subscriberCount());
        assertEquals(1, snapshot.actorEventSubscribers().receivedEvents());
        assertEquals(1, snapshot.actorEventSubscribers().enqueuedEvents());
        assertEquals(1, snapshot.actorEventSubscribers().failedEvents());
        assertTrue(json.contains("\"actorEventSubscribers\":{\"subscriberCount\":1"));
        assertTrue(metrics.contains("commonbattle_actor_event_failed_total 1"));
    }

    @Test
    void snapshotAggregatesOwnerActorEventSubscriptionStatsAndDegradesOnRepairFailure() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        OwnerActorEventSubscriptionView subscription = () -> new OwnerActorEventSubscriptionStats(
                2,
                5,
                7,
                3,
                2,
                1,
                4,
                0,
                2,
                3,
                1
        );
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(subscription);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.ownerActorEventSubscriptions().subscriptionCount());
        assertEquals(2, snapshot.ownerActorEventSubscriptions().watchedOwners());
        assertEquals(5, snapshot.ownerActorEventSubscriptions().watchReferences());
        assertEquals(1, snapshot.ownerActorEventSubscriptions().repairFailures());
        assertTrue(json.contains("\"ownerActorEventSubscriptions\":{\"subscriptionCount\":1"));
        assertTrue(metrics.contains("commonbattle_owner_actor_event_repair_failures_total 1"));
    }

    @Test
    void shopRuntimeStatsAreReportedAndOrderConflictsDegradeHealth() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        ShopRuntimeView shops = () -> new ShopRuntimeStats(3, 1, 1, 0, 0, 0, 0, 0, 1, 1,
                2, 4, 6, 0);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(shops);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.shopRuntimes().runtimeCount());
        assertEquals(3, snapshot.shopRuntimes().purchaseRequests());
        assertEquals(1, snapshot.shopRuntimes().successfulPurchases());
        assertEquals(1, snapshot.shopRuntimes().idempotentReplays());
        assertEquals(1, snapshot.shopRuntimes().orderConflicts());
        assertEquals(1, snapshot.shopRuntimes().recordedOrders());
        assertEquals(2, snapshot.shopRuntimes().activeReservations());
        assertEquals(4, snapshot.shopRuntimes().reservationReapRuns());
        assertEquals(6, snapshot.shopRuntimes().reapedReservations());
        assertTrue(json.contains("\"shopRuntimes\":{\"runtimeCount\":1,\"purchaseRequests\":3"));
        assertTrue(metrics.contains("commonbattle_shop_purchase_requests_total 3"));
        assertTrue(metrics.contains("commonbattle_shop_order_conflicts_total 1"));
        assertTrue(metrics.contains("commonbattle_shop_active_reservations 2"));
        assertTrue(metrics.contains("commonbattle_shop_reaped_reservations_total 6"));
    }

    @Test
    void asyncShopPurchaseStatsAreReportedAndChainFailuresDegradeHealth() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        AsyncShopPurchaseView purchases = () -> new AsyncShopPurchaseStats(4, 1, 3, 1, 1, 1, 1, 2, 2, 1, 1);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(purchases);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.asyncShopPurchases().viewCount());
        assertEquals(4, snapshot.asyncShopPurchases().startedPurchases());
        assertEquals(3, snapshot.asyncShopPurchases().stockReservations());
        assertEquals(1, snapshot.asyncShopPurchases().rpcFailures());
        assertEquals(1, snapshot.asyncShopPurchases().lateCallbacks());
        assertEquals(1, snapshot.asyncShopPurchases().releaseFailures());
        assertTrue(json.contains("\"asyncShopPurchases\":{\"viewCount\":1,\"startedPurchases\":4"));
        assertTrue(metrics.contains("commonbattle_async_shop_purchase_started_total 4"));
        assertTrue(metrics.contains("commonbattle_async_shop_purchase_late_callbacks_total 1"));
        assertTrue(metrics.contains("commonbattle_async_shop_purchase_release_failures_total 1"));
    }

    @Test
    void asyncShopPurchaseBusinessRejectsDoNotDegradeHealth() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        AsyncShopPurchaseView purchases = () -> new AsyncShopPurchaseStats(2, 0, 2, 0, 2, 0, 0, 0, 2, 0, 0);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(purchases);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.UP, snapshot.status());
        assertEquals(2, snapshot.asyncShopPurchases().outOfStockCallbacks());
        assertEquals(2, snapshot.asyncShopPurchases().rejectedPurchases());
        assertTrue(metrics.contains("commonbattle_async_shop_purchase_out_of_stock_callbacks_total 2"));
        assertTrue(metrics.contains("commonbattle_async_shop_purchase_rejected_total 2"));
    }

    @Test
    void businessResponseStatsAreReportedAndTimeoutsDegradeHealth() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        MutableClock clock = new MutableClock(CLOCK.instant());
        PlayerBusinessResponseHub responses = new PlayerBusinessResponseHub(
                com.commonbattle.game.player.PlayerBusinessResultSink.NOOP,
                clock
        );
        PlayerCommand command = businessCommand(1);
        responses.expect(command, ignored -> {
        });
        responses.timeout(command, Duration.ofSeconds(5));
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(responses);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.businessResponses().hubCount());
        assertEquals(1, snapshot.businessResponses().submittedResponses());
        assertEquals(1, snapshot.businessResponses().timedOutResponses());
        assertEquals(0, snapshot.businessResponses().pendingResponses());
        assertEquals(1, snapshot.businessResponses().cachedResponses());
        assertTrue(json.contains("\"businessResponses\":{\"hubCount\":1,\"pendingResponses\":0"));
        assertTrue(metrics.contains("commonbattle_player_business_response_timed_out_total 1"));
        assertTrue(metrics.contains("commonbattle_player_business_response_cached 1"));
    }

    @Test
    void oldBusinessResponseWaiterCanDegradeHealthByPolicy() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        MutableClock clock = new MutableClock(CLOCK.instant());
        PlayerBusinessResponseHub responses = new PlayerBusinessResponseHub(
                com.commonbattle.game.player.PlayerBusinessResultSink.NOOP,
                clock
        );
        responses.expect(businessCommand(1), ignored -> {
        });
        clock.advance(Duration.ofMillis(80));
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(responses);
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                clock,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        clock
                ),
                new InMemoryVersionedEventOutbox(clock),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                registry,
                new RuntimeHealthPolicy(10_000, 0, 300_000, 0, 0, 0, 10, 50)
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.businessResponses().pendingResponses());
        assertEquals(80, snapshot.businessResponses().oldestPendingAgeMillis());
        assertTrue(metrics.contains("commonbattle_player_business_response_pending 1"));
        assertTrue(metrics.contains("commonbattle_player_business_response_oldest_pending_age_millis 80"));
    }

    @Test
    void sceneRuntimeStatsAreReported() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        SceneRuntimeView first = () -> new SceneRuntimeStats(2, 30, 8, 12, 4, 2, 1, 0);
        SceneRuntimeView second = () -> new SceneRuntimeStats(1, 10, 4, 7, 3, 1, 2, 0);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(java.util.List.of(first, second));
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.UP, snapshot.status());
        assertEquals(2, snapshot.sceneRuntimes().runtimeCount());
        assertEquals(3, snapshot.sceneRuntimes().activeScenes());
        assertEquals(40, snapshot.sceneRuntimes().activePlayers());
        assertEquals(12, snapshot.sceneRuntimes().shardCount());
        assertEquals(12, snapshot.sceneRuntimes().maxShardPlayers());
        assertEquals(7, snapshot.sceneRuntimes().playerInterests());
        assertEquals(3, snapshot.sceneRuntimes().allianceReferences());
        assertEquals(3, snapshot.sceneRuntimes().duplicateEnters());
        assertEquals(0, snapshot.sceneRuntimes().missingLeaves());
        assertTrue(json.contains("\"sceneRuntimes\":{\"runtimeCount\":2,\"activeScenes\":3,\"activePlayers\":40"));
        assertTrue(metrics.contains("commonbattle_scene_runtimes 2"));
        assertTrue(metrics.contains("commonbattle_scene_active_scenes 3"));
        assertTrue(metrics.contains("commonbattle_scene_active_players 40"));
        assertTrue(metrics.contains("commonbattle_scene_max_shard_players 12"));
        assertTrue(metrics.contains("commonbattle_scene_player_interests 7"));
        assertTrue(metrics.contains("commonbattle_scene_alliance_references 3"));
        assertTrue(metrics.contains("commonbattle_scene_duplicate_enters_total 3"));
    }

    @Test
    void sceneRuntimeThresholdsDegradeHealth() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        SceneRuntimeView scenes = () -> new SceneRuntimeStats(3, 40, 8, 12);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(scenes);
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
                registry,
                new RuntimeHealthPolicy(10_000, 0, 300_000, 2, 0, 10)
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(3, snapshot.sceneRuntimes().activeScenes());
        assertEquals(12, snapshot.sceneRuntimes().maxShardPlayers());
    }

    @Test
    void sceneMissingLeavesDegradeHealth() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        SceneRuntimeView scenes = () -> new SceneRuntimeStats(1, 1, 1, 1, 0, 0, 0, 1);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(scenes);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.sceneRuntimes().missingLeaves());
        assertTrue(metrics.contains("commonbattle_scene_missing_leaves_total 1"));
    }

    @Test
    void shopReservationReapFailuresDegradeHealth() {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        ShopRuntimeView shops = () -> new ShopRuntimeStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                1, 2, 0, 1);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(shops);
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
                registry,
                RuntimeHealthPolicy.defaults()
        );

        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String metrics = RuntimeMetricsFormatter.format(snapshot);

        assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
        assertEquals(1, snapshot.shopRuntimes().reservationReapFailures());
        assertTrue(metrics.contains("commonbattle_shop_reservation_reap_failures_total 1"));
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", 9000),
                Set.of(),
                Map.of()
        );
    }

    private static ServiceDescriptor withRouteMetadata(ServiceDescriptor descriptor, String routeTag, String deploymentGroup) {
        return descriptor.withMetadata(Map.of(
                ServiceMetadata.ROUTE_TAG, routeTag,
                ServiceMetadata.DEPLOYMENT_GROUP, deploymentGroup
        ));
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

    private static AgentMigrationTask migrationTask(
            String taskId,
            AgentMigrationTaskStatus status,
            Instant updatedAt
    ) {
        return new AgentMigrationTask(
                taskId,
                AgentIdentity.player(10001L),
                new AgentLocation(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new ActorRef("player-10001")),
                new AgentLocation(ServiceId.of(ServiceKind.GAME, "r1", "game-2"), new ActorRef("player-10001")),
                new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1}),
                status,
                "",
                updatedAt
        );
    }

    private static AgentMigrationRecoveryService emptyRecoveryService(ActorSystem actors) {
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        return new AgentMigrationRecoveryService(
                AgentMigrationTaskStore.none(),
                directory,
                new AgentLifecycleManager(local, actors, directory, CLOCK),
                (target, request) -> AgentMigrationAcceptResponse.success(),
                new InlineExecutor(),
                AgentMigrationPolicy.defaults(),
                CLOCK
        );
    }

    private static AgentMigrationRecoveryService failingRecoveryService(ActorSystem actors) {
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        return new AgentMigrationRecoveryService(
                new FailingMigrationTaskStore(),
                directory,
                new AgentLifecycleManager(local, actors, directory, CLOCK),
                (target, request) -> AgentMigrationAcceptResponse.success(),
                new InlineExecutor(),
                AgentMigrationPolicy.defaults(),
                CLOCK
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

    private static PlayerCommand businessCommand(long sequence) {
        return new PlayerCommand(
                10001L,
                "session-1",
                1,
                sequence,
                PlayerBusinessOperations.ACTIVITY_PROGRESS,
                new ActivityProgressCommand("kill-3", 1)
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
        public java.time.ZoneId getZone() {
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

    private static final class ImmediateOptionedGateway implements OptionedRpcGateway {
        @Override
        public <T> void call(com.commonbattle.actor.rpc.RpcRequest<T> request, com.commonbattle.actor.rpc.RpcCallback<T> callback) {
            call(request, callback, RpcCallOptions.of(Duration.ofSeconds(1)));
        }

        @Override
        public <T> void call(
                com.commonbattle.actor.rpc.RpcRequest<T> request,
                com.commonbattle.actor.rpc.RpcCallback<T> callback,
                RpcCallOptions options
        ) {
            callback.success(request.responseType().cast("ok"));
        }
    }

    private static final class FailingMigrationTaskStore implements AgentMigrationTaskStore {
        @Override
        public void save(AgentMigrationTask task) {
        }

        @Override
        public java.util.Optional<AgentMigrationTask> claim(String taskId, String owner, Instant now, Duration leaseTtl) {
            return java.util.Optional.empty();
        }

        @Override
        public void mark(String taskId, AgentMigrationTaskStatus status, String reason, Instant now) {
        }

        @Override
        public java.util.List<AgentMigrationTask> pendingTasks() {
            throw new IllegalStateException("store unavailable");
        }

        @Override
        public int purgeTerminalTasksBefore(Instant cutoff) {
            return 0;
        }

        @Override
        public AgentMigrationTaskStoreStats stats(Instant now) {
            return AgentMigrationTaskStoreStats.empty();
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

    private static final class FailingRegisterRegistry implements ServiceRegistry {
        @Override
        public void register(ServiceDescriptor service) {
            throw new IllegalStateException("registry down");
        }

        @Override
        public void register(ServiceDescriptor service, Duration leaseTtl) {
            throw new IllegalStateException("registry down");
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
