package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorScheduleRegistry;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.backpressure.AgentRateLimitPolicy;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.config.GameConfigChangedEvent;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.player.BattleStageClearCommand;
import com.commonbattle.game.player.InMemoryPlayerStateRepository;
import com.commonbattle.game.player.PlayerBusinessOperations;
import com.commonbattle.game.player.PlayerBusinessResponse;
import com.commonbattle.game.player.PlayerBusinessResponseStatus;
import com.commonbattle.game.player.PlayerProfile;
import com.commonbattle.game.player.UseExpItemsCommand;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.profile.InMemoryProfileSnapshotRepository;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandResult;
import com.commonbattle.game.session.PlayerCommandStatus;
import com.commonbattle.game.session.PlayerLoginResult;
import com.commonbattle.game.social.AllianceMemberRequest;
import com.commonbattle.game.social.AllianceSnapshot;
import com.commonbattle.game.social.FriendChangedEvent;
import com.commonbattle.game.social.FriendRelationRequest;
import com.commonbattle.game.social.FriendRelationAction;
import com.commonbattle.game.social.FriendSnapshot;
import com.commonbattle.game.social.InMemoryAllianceSnapshotRepository;
import com.commonbattle.game.social.InMemoryFriendSnapshotRepository;
import com.commonbattle.game.social.SocialAgentOperations;
import com.commonbattle.observability.RuntimeHealthJsonFormatter;
import com.commonbattle.observability.RuntimeHealthPolicy;
import com.commonbattle.observability.RuntimeHealthProbe;
import com.commonbattle.observability.RuntimeHealthSnapshot;
import com.commonbattle.observability.RuntimeMetricsFormatter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootGamePlayerRuntimeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant SERVER_OPEN_TIME = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void configuredPlayerRuntimeCanLoginAndDispatchBusinessCommand() {
        BootRuntime runtime = new BootRuntime();
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = runtime.add("actors", new ActorSystem(executor, 64));
        ServiceDescriptor local = ClusterDescriptors.fromConfig(ClusterNodeConfig.fromProperties(properties()));
        LocalGameConfigCache configCache = runtime.add("configCache",
                new LocalGameConfigCache(new GameConfigValidator(), CLOCK));
        configCache.apply(GameConfigChangedEvent.activePublished(1, ExampleGameConfigs.basic(7, CLOCK.instant())));
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        InMemoryPlayerStateRepository stateRepository = new InMemoryPlayerStateRepository();
        InMemoryProfileSnapshotRepository profileSnapshots = new InMemoryProfileSnapshotRepository();
        InMemoryFriendSnapshotRepository friendSnapshots = new InMemoryFriendSnapshotRepository();
        InMemoryAllianceSnapshotRepository allianceSnapshots = new InMemoryAllianceSnapshotRepository();
        PlayerProfile seed = new PlayerProfile(10001L, SERVER_OPEN_TIME);
        seed.bag().restore(new BagSnapshot(Map.of("exp_potion", 3)));
        stateRepository.save(10001L, seed.snapshot(4, 2, CLOCK.instant()));
        List<VersionedEvent> publishedEvents = new ArrayList<>();

        BootGamePlayerRuntime players = BootGamePlayerRuntime.configure(
                runtime,
                local,
                actors,
                new NoopRpcGateway(),
                directory,
                stateRepository,
                configCache,
                profileSnapshots,
                friendSnapshots,
                allianceSnapshots,
                publishedEvents::add,
                publishedEvents::add,
                publishedEvents::add,
                CLOCK,
                SERVER_OPEN_TIME,
                AgentRateLimitPolicy.perSecond(100, 100)
        );

        PlayerLoginResult login = players.logins().login(10001L, "session-1");
        executor.runAll();
        PlayerCommandResult dispatch = players.dispatcher().dispatch(new PlayerCommand(
                10001L,
                login.session().sessionId(),
                login.session().epoch(),
                1,
                PlayerBusinessOperations.BATTLE_CLEAR_STAGE,
                new BattleStageClearCommand("settle-10001-1", "forest-1")
        ));
        executor.runAll();
        PlayerCommandResult growth = players.dispatcher().dispatch(new PlayerCommand(
                10001L,
                login.session().sessionId(),
                login.session().epoch(),
                2,
                PlayerBusinessOperations.GROWTH_USE_EXP_ITEMS,
                new UseExpItemsCommand(2)
        ));
        executor.runAll();
        RecordingBusinessCallback callback = new RecordingBusinessCallback();
        players.businessMessages().sendPlayerCommand(new PlayerCommand(
                10001L,
                login.session().sessionId(),
                login.session().epoch(),
                3,
                PlayerBusinessOperations.GROWTH_USE_EXP_ITEMS,
                new UseExpItemsCommand(1)
        ), callback);

        assertNull(callback.response.get());
        executor.runAll();

        assertEquals(PlayerCommandStatus.ACCEPTED, dispatch.status());
        assertEquals(PlayerCommandStatus.ACCEPTED, growth.status());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, callback.response.get().status());
        assertEquals(4, login.stateRevision());
        assertEquals(2, login.eventRevision());
        assertEquals("player-10001", directory.locate(AgentIdentity.player(10001L)).orElseThrow().actorRef().id());
        assertEquals(30, players.agents().getOrCreate(10001L).profile().bag().count("gold"));
        PlayerDomainVersionedEvent event = assertInstanceOf(PlayerDomainVersionedEvent.class, publishedEvents.getFirst());
        assertEquals(3, event.revision());
        ProfileChangedEvent profileEvent = publishedEvents.stream()
                .filter(ProfileChangedEvent.class::isInstance)
                .map(ProfileChangedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(2, profileEvent.snapshot().level());
        assertTrue(profileEvent.changedFields().contains(ProfileField.LEVEL));
        assertEquals(2, profileSnapshots.find(10001L).orElseThrow().level());
        players.friendAgents().add(10001L, 20002L);
        executor.runAll();
        assertEquals(java.util.Set.of(20002L), friendSnapshots.find(10001L).orElseThrow().friends());
        assertEquals(new FriendBrief(1, 1), profileSnapshots.find(10001L).orElseThrow().friends());
        FriendChangedEvent friendEvent = publishedEvents.stream()
                .filter(FriendChangedEvent.class::isInstance)
                .map(FriendChangedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(FriendRelationAction.ADD, friendEvent.action());
        assertEquals(1, players.friendAgents().stats().loadedAgents());
        assertTrue(publishedEvents.stream()
                .filter(ProfileChangedEvent.class::isInstance)
                .map(ProfileChangedEvent.class::cast)
                .anyMatch(changed -> changed.changedFields().contains(ProfileField.FRIENDS)));
        AtomicReference<FriendSnapshot> friendResponse = new AtomicReference<>();
        players.businessMessages().requestAgent(
                actors.actor("boot-requester"),
                AgentIdentity.friend(10001L),
                SocialAgentOperations.FRIEND_REMOVE,
                new FriendRelationRequest(20002L),
                FriendSnapshot.class,
                successOnly(friendResponse)
        );
        executor.runAll();
        assertEquals(2, friendResponse.get().revision());
        assertEquals(java.util.Set.of(), friendResponse.get().friends());
        assertEquals(java.util.Set.of(), friendSnapshots.find(10001L).orElseThrow().friends());
        players.allianceAgents().getOrCreate(100L);
        executor.runAll();
        AtomicReference<AllianceSnapshot> allianceResponse = new AtomicReference<>();
        players.businessMessages().requestAgent(
                actors.actor("boot-requester"),
                AgentIdentity.alliance(100L),
                SocialAgentOperations.ALLIANCE_JOIN,
                new AllianceMemberRequest(10001L),
                AllianceSnapshot.class,
                successOnly(allianceResponse)
        );
        executor.runAll();
        assertEquals(1, allianceResponse.get().revision());
        assertEquals(java.util.Set.of(10001L), allianceResponse.get().members());
        assertEquals(java.util.Set.of(10001L), allianceSnapshots.find(100L).orElseThrow().members());
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                CLOCK,
                actors,
                players.lifecycles(),
                new InMemoryVersionedEventOutbox(CLOCK),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                runtime.healthRegistry(),
                RuntimeHealthPolicy.defaults()
        );
        RuntimeHealthSnapshot snapshot = probe.snapshot();
        String json = RuntimeHealthJsonFormatter.format(snapshot);
        String metrics = RuntimeMetricsFormatter.format(snapshot);
        assertEquals(1, snapshot.playerAgents().managerCount());
        assertEquals(1, snapshot.playerAgents().loadedAgents());
        assertEquals(1, snapshot.playerOutboundDeliveries().runtimeCount());
        assertEquals(0, snapshot.playerAgents().autoSaveSchedulers());
        assertEquals(1, snapshot.playerAgents().drainServices());
        assertEquals(1, snapshot.asyncShopPurchases().viewCount());
        assertTrue(json.contains("\"playerAgents\":{\"managerCount\":1,\"loadedAgents\":1"));
        assertTrue(metrics.contains("commonbattle_player_agents_loaded 1"));
        assertTrue(metrics.contains("commonbattle_player_agent_drain_services 1"));
        assertTrue(metrics.contains("commonbattle_async_shop_purchase_views 1"));
        assertTrue(players.logins().logoutAndPassivate(login.session(), ignored -> {
        }));
        executor.runAll();
        assertEquals(2, profileSnapshots.find(10001L).orElseThrow().level());
        assertEquals(1, runtime.healthRegistry().commandDispatchers().size());
        assertEquals(1, runtime.healthRegistry().playerOutboundDeliveries().size());
        assertEquals(1, runtime.healthRegistry().commandAudits().size());
        assertEquals(1, runtime.healthRegistry().lifecycleManagers().size());
        assertEquals(2, runtime.healthRegistry().actorMailboxPressures().size());
        assertNull(players.autoSaves());
        assertTrue(runtime.healthRegistry().drainableComponents().contains(players.logins()));
        assertTrue(runtime.healthRegistry().drainableComponents().contains(players.dispatcher()));
        assertTrue(runtime.healthRegistry().drainableComponents().contains(players.drain()));
    }

    @Test
    void clusterConfiguredPlayerRuntimeRegistersRpcRoutePolicyView() {
        BootRuntime runtime = new BootRuntime();
        ActorSystem actors = runtime.add("actors", new ActorSystem(new RecordingExecutor(), 64));
        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties());
        ServiceDescriptor local = ClusterDescriptors.fromConfig(config);
        ClusterRpcGateway gateway = runtime.add("gateway", new ClusterRpcGateway(
                local,
                new ClusterDirectory(new InMemoryServiceRegistry()),
                new ClusterTopology(),
                new LocalClusterTransport(),
                false
        ));
        LocalGameConfigCache configCache = runtime.add("configCache",
                new LocalGameConfigCache(new GameConfigValidator(), CLOCK));

        BootGamePlayerRuntime.configure(
                runtime,
                config,
                local,
                actors,
                gateway,
                configCache,
                new InMemoryProfileSnapshotRepository(),
                new InMemoryFriendSnapshotRepository(),
                new InMemoryAllianceSnapshotRepository(),
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                CLOCK
        );

        assertEquals(1, runtime.healthRegistry().rpcRoutePolicies().size());
    }

    @Test
    void clusterConfiguredAutoSaveUsesActorScheduleRegistry() {
        BootRuntime runtime = new BootRuntime();
        try {
            ActorSystem actors = runtime.add("actors", new ActorSystem(new RecordingExecutor(), 64));
            ActorScheduleRegistry schedules = BootActorSchedules.configure(runtime, actors);
            Properties properties = properties();
            properties.setProperty("cluster.player.auto.save.enabled", "true");
            properties.setProperty("cluster.player.auto.save.initial.delay.millis", "60000");
            properties.setProperty("cluster.player.auto.save.interval.millis", "60000");
            ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);
            ServiceDescriptor local = ClusterDescriptors.fromConfig(config);
            ClusterRpcGateway gateway = runtime.add("gateway", new ClusterRpcGateway(
                    local,
                    new ClusterDirectory(new InMemoryServiceRegistry()),
                    new ClusterTopology(),
                    new LocalClusterTransport(),
                    false
            ));
            LocalGameConfigCache configCache = runtime.add("configCache",
                    new LocalGameConfigCache(new GameConfigValidator(), CLOCK));

            BootGamePlayerRuntime.configure(
                    runtime,
                    config,
                    local,
                    actors,
                    gateway,
                    configCache,
                    new InMemoryProfileSnapshotRepository(),
                    new InMemoryFriendSnapshotRepository(),
                    new InMemoryAllianceSnapshotRepository(),
                    ignored -> {
                    },
                    ignored -> {
                    },
                    ignored -> {
                    },
                    schedules,
                    CLOCK
            );

            assertEquals(1, runtime.healthRegistry().playerAutoSaves().size());
            assertEquals(1, schedules.stats().activeJobs());
            assertEquals(1, schedules.stats().scheduledJobs());
        } finally {
            runtime.close();
        }
    }

    @Test
    void clusterConfiguredGrowthStaminaRecoverySchedulesPerLoadedPlayer() {
        BootRuntime runtime = new BootRuntime();
        RecordingExecutor executor = new RecordingExecutor();
        try {
            ActorSystem actors = runtime.add("actors", new ActorSystem(executor, 64));
            ActorScheduleRegistry schedules = BootActorSchedules.configure(runtime, actors);
            Properties properties = properties();
            properties.setProperty("cluster.player.growth.stamina.recovery.enabled", "true");
            properties.setProperty("cluster.player.growth.stamina.recovery.initial.delay.millis", "60000");
            properties.setProperty("cluster.player.growth.stamina.recovery.interval.millis", "60000");
            ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);
            ServiceDescriptor local = ClusterDescriptors.fromConfig(config);
            LocalGameConfigCache configCache = runtime.add("configCache",
                    new LocalGameConfigCache(new GameConfigValidator(), CLOCK));
            configCache.apply(GameConfigChangedEvent.activePublished(1, ExampleGameConfigs.basic(7, CLOCK.instant())));

            BootGamePlayerRuntime players = BootGamePlayerRuntime.configure(
                    runtime,
                    local,
                    actors,
                    new NoopRpcGateway(),
                    new InMemoryAgentDirectory(),
                    new InMemoryPlayerStateRepository(),
                    configCache,
                    new InMemoryProfileSnapshotRepository(),
                    new InMemoryFriendSnapshotRepository(),
                    new InMemoryAllianceSnapshotRepository(),
                    ignored -> {
                    },
                    ignored -> {
                    },
                    ignored -> {
                    },
                    (com.commonbattle.game.shop.ShopStockAsyncClient) null,
                    CLOCK,
                    SERVER_OPEN_TIME,
                    AgentRateLimitPolicy.perSecond(100, 100),
                    schedules,
                    false,
                    Duration.ZERO,
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(5),
                    512,
                    true,
                    config.playerGrowthStaminaRecoveryInitialDelay(),
                    config.playerGrowthStaminaRecoveryInterval()
            );

            PlayerLoginResult login = players.logins().login(10001L, "session-1");
            executor.runAll();

            assertEquals(1, schedules.stats().activeJobs());
            assertTrue(players.logins().logoutAndPassivate(login.session(), ignored -> {
            }));
            executor.runAll();
            assertEquals(0, schedules.stats().activeJobs());
        } finally {
            runtime.close();
        }
    }

    private static Properties properties() {
        Properties properties = new Properties();
        properties.setProperty("cluster.kind", "GAME");
        properties.setProperty("cluster.region", "r1");
        properties.setProperty("cluster.node", "game-1");
        properties.setProperty("cluster.host", "127.0.0.1");
        properties.setProperty("cluster.port", "9001");
        properties.setProperty("cluster.center.host", "127.0.0.1");
        properties.setProperty("cluster.center.port", "9000");
        properties.setProperty("cluster.actor.workers", "4");
        return properties;
    }

    private static <T> com.commonbattle.actor.message.LocalAskCallback<T> successOnly(AtomicReference<T> response) {
        return new com.commonbattle.actor.message.LocalAskCallback<>() {
            @Override
            public void success(com.commonbattle.actor.ActorContext context, T result) {
                response.set(result);
            }

            @Override
            public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
                throw new AssertionError(error);
            }
        };
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runAll() {
            while (!commands.isEmpty()) {
                commands.removeFirst().run();
            }
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }

    private static final class RecordingBusinessCallback implements RpcCallback<PlayerBusinessResponse> {
        private final AtomicReference<PlayerBusinessResponse> response = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public void success(PlayerBusinessResponse response) {
            this.response.set(response);
        }

        @Override
        public void failure(Throwable error) {
            failure.set(error);
        }
    }
}
