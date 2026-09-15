package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.message.ExecutorAskTimeoutScheduler;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterNode;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.event.ClusterEventSubscriptionLeaseRenewer;
import com.commonbattle.cluster.event.ClusterEventSubscriptionManager;
import com.commonbattle.cluster.event.ClusterVersionedEventBus;
import com.commonbattle.cluster.event.EventReplayRepairer;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.registry.RemoteServiceRegistry;
import com.commonbattle.cluster.registry.ServiceDescriptorPublisher;
import com.commonbattle.cluster.rpc.ClusterRpcDeliveryFailureMapper;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.cross.EnterSceneRequest;
import com.commonbattle.example.cross.EnterSceneResult;
import com.commonbattle.example.cross.LeaveSceneRequest;
import com.commonbattle.example.cross.LeaveSceneResult;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.example.cross.scene.LargeSceneShardService;
import com.commonbattle.example.cross.scene.MultiSmallSceneService;
import com.commonbattle.example.cross.scene.ProfileAwareSceneService;
import com.commonbattle.example.cross.scene.SceneHostingMode;
import com.commonbattle.example.cross.scene.ScenePlacement;
import com.commonbattle.example.cross.scene.SceneRuntimeMetadata;
import com.commonbattle.example.cross.scene.SceneServiceStrategy;
import com.commonbattle.game.config.GameConfigAutoRecovery;
import com.commonbattle.game.config.GameConfigChangedEvent;
import com.commonbattle.game.config.GameConfigEventReplayRepairer;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.GameConfigWarmupResult;
import com.commonbattle.game.config.GameConfigWarmupService;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.config.RemoteGameConfigRecoveryClient;
import com.commonbattle.game.event.ActorMailboxEventSubscriber;
import com.commonbattle.game.event.OwnerActorEventSubscription;
import com.commonbattle.game.profile.LocalProfileCache;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileEventReplayRepairer;
import com.commonbattle.game.profile.ProfileOwnerEventInterests;
import com.commonbattle.game.profile.ProfileOwnerKeyParser;
import com.commonbattle.game.profile.ProfileRuntime;
import com.commonbattle.game.profile.ProfileSnapshotReader;
import com.commonbattle.game.profile.RemoteProfileSnapshotReader;
import com.commonbattle.game.profile.SceneProfileSnapshotRepairer;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.scene.SceneFriendAwarenessAgent;
import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import com.commonbattle.game.snapshot.EventReplaySnapshotRepairer;
import com.commonbattle.game.social.AllianceMemberChangedEvent;
import com.commonbattle.game.social.AllianceOwnerKeyParser;
import com.commonbattle.game.social.AllianceSnapshotRepairer;
import com.commonbattle.game.social.FriendChangedEvent;
import com.commonbattle.game.social.FriendOwnerKeyParser;
import com.commonbattle.game.social.FriendSnapshotRepairer;
import com.commonbattle.game.social.RemoteAllianceSnapshotReader;
import com.commonbattle.game.social.RemoteFriendSnapshotReader;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Scene 服启动入口。
 */
public final class SceneServerMain {
    private SceneServerMain() {
    }

    public static void main(String[] args) throws Exception {
        BootRuntime runtime = new BootRuntime().installShutdownHook();
        try {
            ClusterNodeConfig config = ClusterNodeConfig.load(args, "cluster/scene-small.properties");
            config.validate(ServiceKind.SCENE).throwIfInvalid();
            ActorSystem actors = runtime.add("actors", new ActorSystem(config.actorSystemConfig()));
            SceneServiceStrategy sceneService = sceneService(config, actors);
            ServiceDescriptor local = ClusterDescriptors.withConfigMetadata(sceneService.descriptor(), config);
            ServiceDescriptor center = ClusterDescriptors.center(config);
            ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
            directory.seed(center);
            PayloadCodecRegistry codecs = BootPayloadCodecs.clusterServer();
            NettyClusterTransport transport = runtime.add("nettyTransport", new NettyClusterTransport(
                    new DirectoryEndpointView(directory, local, center),
                    codecs
            ));
            ClusterRpcGateway gateway = runtime.add("rpcGateway",
                    new ClusterRpcGateway(local, directory, ClusterTopology.defaultCrossServer(), transport));
            RemoteServiceRegistry registry = new RemoteServiceRegistry(
                    local.id(),
                    gateway,
                    directory,
                    config.registrySubscriptionLeaseTtl()
            );
            BootRegistryRecovery.configure(runtime, config, registry);
            ClusterNode node = runtime.add("clusterNode", new ClusterNode(registry, local, directory));
            BootAgentMigrationTasks.configure(runtime, config, Clock.systemUTC());
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
            LocalProfileCache sceneProfileCache = new LocalProfileCache();
            ExecutorService profileRepairExecutor = runtime.add("profileRepairExecutor", Executors.newFixedThreadPool(
                    profileRepairWorkers(config),
                    new NamedThreadFactory("common-battle-profile-repair")
            ));
            ProfileSnapshotReader profileSnapshotReader = new RemoteProfileSnapshotReader(gateway, Duration.ofSeconds(1));
            ProfileOwnerEventInterests profileInterests = new ProfileOwnerEventInterests();
            runtime.observe("profileInterests", profileInterests);
            ProfileRuntime profileRuntime = new ProfileRuntime(sceneProfileCache, profileInterests, profileSnapshotReader);
            runtime.observe("profileRuntime", profileRuntime);
            DefaultAgentMessagePort profileMessagePort = runtime.add(
                    "profileMessagePort",
                    new DefaultAgentMessagePort(
                            actors,
                            gateway,
                            new ExecutorAskTimeoutScheduler(),
                            new ClusterRpcDeliveryFailureMapper()
                    )
            );
            ActorRef profileActor = actors.actor("scene-profile:" + local.id().node());
            ActorRef domainEventActor = actors.actor("scene-domain-events:" + local.id().node());
            ActorRef allianceEventActor = actors.actor("scene-alliance-events:" + local.id().node());
            ActorRef friendEventActor = actors.actor("scene-friend-events:" + local.id().node());
            SceneProfileAwarenessAgent profileAwareness = new SceneProfileAwarenessAgent(
                    profileMessagePort,
                    profileActor,
                    profileRuntime
            );
            ScenePlayerDomainEventAgent domainAwareness = new ScenePlayerDomainEventAgent(
                    profileMessagePort,
                    domainEventActor
            );
            SceneAllianceAwarenessAgent allianceAwareness = new SceneAllianceAwarenessAgent(
                    profileMessagePort,
                    allianceEventActor
            );
            SceneFriendAwarenessAgent friendAwareness = new SceneFriendAwarenessAgent(
                    profileMessagePort,
                    friendEventActor
            );
            ActorMailboxEventSubscriber profileEventSubscriber = new ActorMailboxEventSubscriber(
                    profileMessagePort,
                    profileActor,
                    (context, event) -> {
                        if (!(event instanceof ProfileChangedEvent profileEvent)) {
                            throw new IllegalArgumentException("event must be ProfileChangedEvent");
                        }
                        profileAwareness.handleProfileChanged(profileEvent);
                    }
            );
            runtime.observe("profileActorEventSubscriber", profileEventSubscriber);
            OwnerActorEventSubscription profileEventInterests = runtime.add("profileEventInterests",
                    new OwnerActorEventSubscription(
                            eventBus,
                            ProfileChangedEvent.TOPIC,
                            profileEventSubscriber,
                            ownerKey -> ProfileOwnerKeyParser.INSTANCE.parse(ownerKey)
                                    .stream()
                                    .map(sceneProfileCache::revisionOf)
                                    .findFirst()
                                    .orElse(0L),
                            new ProfileEventReplayRepairer(
                                    new SceneProfileSnapshotRepairer(profileSnapshotReader, profileAwareness)),
                            profileRepairExecutor
                    ));
            profileInterests.attach(profileEventInterests, profileEventInterests);
            ActorMailboxEventSubscriber domainEventSubscriber = new ActorMailboxEventSubscriber(
                    profileMessagePort,
                    domainEventActor,
                    (context, event) -> {
                        if (!(event instanceof PlayerDomainVersionedEvent playerEvent)) {
                            throw new IllegalArgumentException("event must be PlayerDomainVersionedEvent");
                        }
                        domainAwareness.handlePlayerDomainEvent(playerEvent);
                    }
            );
            runtime.observe("domainActorEventSubscriber", domainEventSubscriber);
            OwnerActorEventSubscription domainEventInterests = runtime.add("domainEventInterests",
                    new OwnerActorEventSubscription(
                            eventBus,
                            PlayerDomainVersionedEvent.TOPIC,
                            domainEventSubscriber,
                            domainAwareness.processor()::revisionOf,
                            EventReplayRepairer.noop(),
                            profileRepairExecutor
                    ));
            domainAwareness.attachInterests(domainEventInterests);
            ActorMailboxEventSubscriber allianceEventSubscriber = new ActorMailboxEventSubscriber(
                    profileMessagePort,
                    allianceEventActor,
                    (context, event) -> {
                        if (!(event instanceof AllianceMemberChangedEvent allianceEvent)) {
                            throw new IllegalArgumentException("event must be AllianceMemberChangedEvent");
                        }
                        allianceAwareness.handleAllianceChanged(allianceEvent);
                    }
            );
            runtime.observe("allianceActorEventSubscriber", allianceEventSubscriber);
            OwnerActorEventSubscription allianceEventInterests = runtime.add("allianceEventInterests",
                    new OwnerActorEventSubscription(
                            eventBus,
                            AllianceMemberChangedEvent.TOPIC,
                            allianceEventSubscriber,
                            ownerKey -> AllianceOwnerKeyParser.INSTANCE.parse(ownerKey)
                                    .stream()
                                    .map(allianceAwareness::revisionOf)
                                    .findFirst()
                                    .orElse(0L),
                            new EventReplaySnapshotRepairer().register(
                                    AllianceMemberChangedEvent.TOPIC,
                                    new AllianceSnapshotRepairer(
                                            new RemoteAllianceSnapshotReader(gateway, Duration.ofSeconds(1)),
                                            allianceAwareness
                                    )
                            ),
                            profileRepairExecutor
                    ));
            allianceAwareness.attachInterests(allianceEventInterests);
            ActorMailboxEventSubscriber friendEventSubscriber = new ActorMailboxEventSubscriber(
                    profileMessagePort,
                    friendEventActor,
                    (context, event) -> {
                        if (!(event instanceof FriendChangedEvent friendEvent)) {
                            throw new IllegalArgumentException("event must be FriendChangedEvent");
                        }
                        friendAwareness.handleFriendChanged(friendEvent);
                    }
            );
            runtime.observe("friendActorEventSubscriber", friendEventSubscriber);
            OwnerActorEventSubscription friendEventInterests = runtime.add("friendEventInterests",
                    new OwnerActorEventSubscription(
                            eventBus,
                            FriendChangedEvent.TOPIC,
                            friendEventSubscriber,
                            ownerKey -> FriendOwnerKeyParser.INSTANCE.parse(ownerKey)
                                    .stream()
                                    .map(friendAwareness::revisionOf)
                                    .findFirst()
                                    .orElse(0L),
                            new EventReplaySnapshotRepairer().register(
                                    FriendChangedEvent.TOPIC,
                                    new FriendSnapshotRepairer(
                                            new RemoteFriendSnapshotReader(gateway, Duration.ofSeconds(1)),
                                            friendAwareness
                                    )
                            ),
                            profileRepairExecutor
                    ));
            friendAwareness.attachInterests(friendEventInterests);
            SceneServiceStrategy activeSceneService = new ProfileAwareSceneService(
                    sceneService,
                    profileAwareness,
                    domainAwareness,
                    allianceAwareness,
                    friendAwareness
            );
            Supplier<ServiceDescriptor> publishedSceneDescriptor = () -> SceneRuntimeMetadata.apply(
                    ClusterDescriptors.withConfigMetadata(activeSceneService.descriptor(), config),
                    activeSceneService.stats(),
                    config.runtimeHealthPolicy()
            );
            runtime.observe("sceneRuntime", activeSceneService);
            gateway.handle(SceneOperations.ENTER, (request, responder) -> {
                EnterSceneRequest payload = (EnterSceneRequest) request.payload();
                ScenePlacement placement = activeSceneService.enter(payload.playerId(), payload.sceneId(), 0, 0);
                responder.success(new EnterSceneResult(payload.playerId(), placement.sceneId(), 0));
            });
            gateway.handle(SceneOperations.LEAVE, (request, responder) -> {
                LeaveSceneRequest payload = (LeaveSceneRequest) request.payload();
                boolean left = activeSceneService.leave(payload.playerId(), payload.sceneId());
                responder.success(new LeaveSceneResult(payload.playerId(), payload.sceneId(), left));
            });
            node.start(
                    List.of(ServiceKind.GAME, ServiceKind.CHAT, ServiceKind.PROXY, ServiceKind.REGION),
                    config.registryLeaseTtl(),
                    config.registryHeartbeatInterval()
            );
            ServiceDescriptorPublisher descriptorPublisher = runtime.add(
                    "descriptorPublisher",
                    new ServiceDescriptorPublisher(
                            registry,
                            publishedSceneDescriptor,
                            config.registryLeaseTtl(),
                            config.registryHeartbeatInterval()
                    )
            );
            descriptorPublisher.start();
            LocalGameConfigCache configCache = runtime.add("configCache", new LocalGameConfigCache(
                    new GameConfigValidator(),
                    Clock.systemUTC()
            ));
            ClusterEventSubscriptionManager eventSubscriptions = runtime.add(
                    "eventSubscriptions",
                    new ClusterEventSubscriptionManager(eventBus)
            );
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
                throw new IllegalStateException("Scene config warmup failed: " + warmup.message());
            }
            runtime.add("opsHttp", BootOpsHttp.start(config, local, actors, directory, runtime.healthRegistry()));
            System.out.println("Scene server started: " + local.id().wireName()
                    + ", config=" + configCache.active().version()
                    + ", ops=" + config.opsEndpoint().host() + ":" + config.opsEndpoint().port());
            new CountDownLatch(1).await();
        } catch (Exception e) {
            runtime.closeSuppressing(e);
            throw e;
        }
    }

    private static SceneServiceStrategy sceneService(ClusterNodeConfig config, ActorSystem actors) {
        if (config.sceneMode() == SceneHostingMode.LARGE_SCENE_SHARD) {
            return LargeSceneShardService.create(
                    actors,
                    config.region(),
                    config.node(),
                    config.endpoint(),
                    config.sceneId(),
                    config.sceneShards()
            );
        }
        return MultiSmallSceneService.create(
                actors,
                config.region(),
                config.node(),
                config.endpoint(),
                config.sceneCapacity()
        );
    }

    private static int profileRepairWorkers(ClusterNodeConfig config) {
        return Math.max(1, Math.min(2, config.actorWorkers()));
    }

    private static final class NamedThreadFactory implements java.util.concurrent.ThreadFactory {
        private final String prefix;
        private final AtomicInteger nextId = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
