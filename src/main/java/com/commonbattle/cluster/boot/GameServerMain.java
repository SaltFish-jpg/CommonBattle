package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.migration.AgentMigrationPayloadCodecs;
import com.commonbattle.actor.agent.remote.AgentDirectoryPayloadCodecs;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterNode;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.event.ClusterEventPayloadCodecs;
import com.commonbattle.cluster.event.ClusterEventSubscriptionLeaseRenewer;
import com.commonbattle.cluster.event.ClusterEventSubscriptionManager;
import com.commonbattle.cluster.event.ClusterVersionedEventBus;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.registry.RegistryPayloadCodecs;
import com.commonbattle.cluster.registry.RemoteServiceRegistry;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.cross.CrossPayloadCodecs;
import com.commonbattle.example.cross.scene.SceneEnterTargetSelector;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.GameConfigAutoRecovery;
import com.commonbattle.game.config.GameConfigChangedEvent;
import com.commonbattle.game.config.GameConfigEventReplayRepairer;
import com.commonbattle.game.config.GameConfigWarmupResult;
import com.commonbattle.game.config.GameConfigWarmupService;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.config.RemoteGameConfigRecoveryClient;
import com.commonbattle.game.event.ReliableVersionedEventPublisher;
import com.commonbattle.game.event.VersionedEventOutbox;
import com.commonbattle.game.chat.ChatPayloadCodecs;
import com.commonbattle.game.profile.InMemoryProfileSnapshotRepository;
import com.commonbattle.game.profile.ProfileSnapshotEndpoint;
import com.commonbattle.game.profile.ProfileSnapshotRepository;
import com.commonbattle.game.profile.ReliableProfileEventPublisher;
import com.commonbattle.game.player.PlayerBusinessCommandPayloadCodecs;
import com.commonbattle.game.shop.ShopStockPayloadCodecs;
import com.commonbattle.game.social.AllianceSnapshotEndpoint;
import com.commonbattle.game.social.AllianceSnapshotRepository;
import com.commonbattle.game.social.FriendSnapshotEndpoint;
import com.commonbattle.game.social.FriendSnapshotRepository;
import com.commonbattle.game.social.InMemoryAllianceSnapshotRepository;
import com.commonbattle.game.social.InMemoryFriendSnapshotRepository;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Game 服启动入口。
 */
public final class GameServerMain {
    private GameServerMain() {
    }

    public static void main(String[] args) throws Exception {
        BootRuntime runtime = new BootRuntime().installShutdownHook();
        try {
            ClusterNodeConfig config = ClusterNodeConfig.load(args, "cluster/game.properties");
            config.validate(ServiceKind.GAME).throwIfInvalid();
            ServiceDescriptor local = ClusterDescriptors.fromConfig(config);
            ServiceDescriptor center = ClusterDescriptors.center(config);
            ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
            directory.seed(center);
            PayloadCodecRegistry codecs = ClusterEventPayloadCodecs.registerTo(
                    ChatPayloadCodecs.registerTo(ShopStockPayloadCodecs.registerTo(PlayerBusinessCommandPayloadCodecs.registerTo(
                            AgentMigrationPayloadCodecs.registerTo(
                                    AgentDirectoryPayloadCodecs.registerTo(RegistryPayloadCodecs.registerTo(CrossPayloadCodecs.create()))
                            )
                    )))
            );
            NettyClusterTransport transport = runtime.add("nettyTransport", new NettyClusterTransport(
                    new DirectoryEndpointView(directory, local, center),
                    codecs
            ));
            ClusterRpcGateway gateway = runtime.add("rpcGateway",
                    new ClusterRpcGateway(local, directory, ClusterTopology.defaultCrossServer(), transport));
            gateway.addTargetSelector(new SceneEnterTargetSelector());
            ProfileSnapshotRepository profileSnapshots = new InMemoryProfileSnapshotRepository();
            new ProfileSnapshotEndpoint(profileSnapshots).bind(gateway);
            AllianceSnapshotRepository allianceSnapshots = new InMemoryAllianceSnapshotRepository();
            new AllianceSnapshotEndpoint(allianceSnapshots).bind(gateway);
            FriendSnapshotRepository friendSnapshots = new InMemoryFriendSnapshotRepository();
            new FriendSnapshotEndpoint(friendSnapshots).bind(gateway);
            RemoteServiceRegistry registry = new RemoteServiceRegistry(
                    local.id(),
                    gateway,
                    directory,
                    config.registrySubscriptionLeaseTtl()
            );
            BootRegistryRecovery.configure(runtime, config, registry);
            ClusterNode node = runtime.add("clusterNode", new ClusterNode(registry, local, directory));
            ActorSystem actors = runtime.add("actors", new ActorSystem(config.actorSystemConfig()));
            BootAgentMigrationTasks.configure(runtime, config, Clock.systemUTC());
            node.start(
                    List.of(ServiceKind.CHAT, ServiceKind.SCENE, ServiceKind.PROXY, ServiceKind.REGION),
                    config.registryLeaseTtl(),
                    config.registryHeartbeatInterval()
            );
            LocalGameConfigCache configCache = runtime.add("configCache", new LocalGameConfigCache(
                    new GameConfigValidator(),
                    Clock.systemUTC()
            ));
            ClusterVersionedEventBus eventBus = new ClusterVersionedEventBus(
                    local.id(),
                    gateway,
                    config.eventSubscriptionLeaseTtl()
            );
            ClusterEventSubscriptionLeaseRenewer eventSubscriptionLeaseRenewer = runtime.add(
                    "eventSubscriptionLeaseRenewer",
                    new ClusterEventSubscriptionLeaseRenewer(eventBus, config.eventSubscriptionLeaseRenewInterval())
            );
            eventSubscriptionLeaseRenewer.start();
            ClusterEventSubscriptionManager eventSubscriptions = runtime.add(
                    "eventSubscriptions",
                    new ClusterEventSubscriptionManager(eventBus)
            );
            VersionedEventOutbox playerEventOutbox = BootEventOutbox.configure(runtime, config, Clock.systemUTC());
            ReliableVersionedEventPublisher playerDomainEvents = new ReliableVersionedEventPublisher(
                    playerEventOutbox,
                    eventBus
            );
            ReliableProfileEventPublisher profileEvents = new ReliableProfileEventPublisher(
                    profileSnapshots,
                    playerEventOutbox,
                    eventBus
            );
            ReliableVersionedEventPublisher friendEvents = new ReliableVersionedEventPublisher(
                    playerEventOutbox,
                    eventBus
            );
            BootEventOutbox.configureReplayScheduler(runtime, config, playerDomainEvents);
            RemoteGameConfigRecoveryClient configRecovery = new RemoteGameConfigRecoveryClient(gateway, configCache);
            GameConfigAutoRecovery configAutoRecovery = new GameConfigAutoRecovery(configRecovery);
            runtime.observe("configAutoRecovery", configAutoRecovery);
            configCache.attachRecoveryTrigger(configAutoRecovery);
            eventSubscriptions.register(
                    GameConfigChangedEvent.TOPIC,
                    configCache,
                    () -> java.util.Map.of(GameConfigChangedEvent.OWNER_KEY, configCache.appliedEventRevision()),
                    new GameConfigEventReplayRepairer(configAutoRecovery, configCache::appliedEventRevision, configCache::stale)
            );
            eventSubscriptions.start();
            GameConfigWarmupResult warmup = new GameConfigWarmupService(
                    configRecovery,
                    Clock.systemUTC()
            ).warmup(config.configWarmupTimeout());
            if (!warmup.ready()) {
                throw new IllegalStateException("Game config warmup failed: " + warmup.message());
            }
            BootGamePlayerRuntime playerRuntime = BootGamePlayerRuntime.configure(
                    runtime,
                    config,
                    local,
                    actors,
                    gateway,
                    configCache,
                    profileSnapshots,
                    friendSnapshots,
                    playerDomainEvents,
                    profileEvents,
                    friendEvents,
                    Clock.systemUTC()
            );
            runtime.add("opsHttp", BootOpsHttp.start(config, local, actors, directory, runtime.healthRegistry()));
            System.out.println("Game server started: " + local.id().wireName()
                    + ", config=" + configCache.active().version()
                    + ", players=" + playerRuntime.agents().loadedAgents()
                    + ", ops=" + config.opsEndpoint().host() + ":" + config.opsEndpoint().port()
                    + ", actors=" + actors);
            new CountDownLatch(1).await();
        } catch (Exception e) {
            runtime.closeSuppressing(e);
            throw e;
        }
    }
}
