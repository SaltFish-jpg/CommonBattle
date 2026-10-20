package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorScheduleRegistry;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.migration.ActorHotspotMigrationPlanner;
import com.commonbattle.actor.agent.migration.ActorHotspotMigrationSubmitService;
import com.commonbattle.actor.agent.migration.AgentMigrationCoordinator;
import com.commonbattle.actor.agent.migration.AgentMigrationExecutor;
import com.commonbattle.actor.agent.migration.AgentMigrationRestoreHandler;
import com.commonbattle.actor.agent.migration.AgentMigrationSourceHook;
import com.commonbattle.actor.agent.migration.AgentMigrationStatePacker;
import com.commonbattle.actor.agent.migration.AgentMigrationTargetEndpoint;
import com.commonbattle.actor.agent.migration.RemoteAgentMigrationClient;
import com.commonbattle.actor.agent.remote.AgentOwnerRpcRouter;
import com.commonbattle.actor.agent.remote.RemoteAgentDirectory;
import com.commonbattle.actor.backpressure.ActorHotspotAdmissionController;
import com.commonbattle.actor.backpressure.ActorMailboxPressureAdmissionController;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.backpressure.InboundAdmissionController;
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
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.registry.RemoteServiceRegistry;
import com.commonbattle.cluster.registry.ServiceDescriptorPublisher;
import com.commonbattle.cluster.rpc.ClusterRpcDeliveryFailureMapper;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcStructuredException;
import com.commonbattle.example.cross.CombatDamageRequest;
import com.commonbattle.example.cross.CombatDamageResult;
import com.commonbattle.example.cross.EnterSceneRequest;
import com.commonbattle.example.cross.EnterSceneResult;
import com.commonbattle.example.cross.LeaveSceneRequest;
import com.commonbattle.example.cross.LeaveSceneResult;
import com.commonbattle.example.cross.MoveSceneRequest;
import com.commonbattle.example.cross.MoveSceneResult;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.example.cross.scene.LargeSceneShardService;
import com.commonbattle.example.cross.scene.MultiSmallSceneService;
import com.commonbattle.example.cross.scene.ProfileAwareSceneService;
import com.commonbattle.example.cross.scene.ProtoSmallSceneAgentSnapshotSerializer;
import com.commonbattle.example.cross.scene.SceneCombatDamageOutcome;
import com.commonbattle.example.cross.scene.SceneHostingMode;
import com.commonbattle.example.cross.scene.SceneMailboxPressureGate;
import com.commonbattle.example.cross.scene.ScenePlacement;
import com.commonbattle.example.cross.scene.SceneRuntimeMetadata;
import com.commonbattle.example.cross.scene.SceneServiceStrategy;
import com.commonbattle.example.cross.scene.SmallSceneAgentMigrationRestoreHandler;
import com.commonbattle.example.cross.scene.SmallSceneAgentMigrationSourceHook;
import com.commonbattle.example.cross.scene.SmallSceneAgentMigrationStatePacker;
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
import com.commonbattle.game.event.OwnerActorEventSubscriptionRecoveryScheduler;
import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.event.OwnerEventRepairDispatcher;
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
import com.commonbattle.game.player.event.RemotePlayerDomainProjectionSnapshotReader;
import com.commonbattle.game.player.event.ScenePlayerDomainSnapshotRepairer;
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
            ActorSystem actors = BootActors.configure(runtime, config, Clock.systemUTC());
            ActorScheduleRegistry actorSchedules = BootActorSchedules.configure(runtime, actors);
            SceneServiceStrategy sceneService = sceneService(config, actors);
            ServiceDescriptor local = ClusterDescriptors.withConfigMetadata(sceneService.descriptor(), config);
            ServiceDescriptor center = ClusterDescriptors.center(config);
            ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
            directory.seed(center);
            PayloadCodecRegistry codecs = BootPayloadCodecs.clusterServer();
            NettyClusterTransport transport = runtime.add("nettyTransport", new NettyClusterTransport(
                    new DirectoryEndpointView(directory, local, center),
                    codecs,
                    config.nettyTransportConfig()
            ));
            ClusterRpcGateway gateway = runtime.add("rpcGateway",
                    new ClusterRpcGateway(local, directory, ClusterTopology.defaultCrossServer(), transport,
                            true, config.rpcGovernanceConfig(),
                            BootRpcIdempotencyStore.configure(config, codecs, Clock.systemUTC())));
            RemoteAgentDirectory agentDirectory = new RemoteAgentDirectory(gateway);
            AgentOwnerRpcRouter ownerRouter = new AgentOwnerRpcRouter(local.id(), agentDirectory, gateway);
            AgentLifecycleManager sceneLifecycles = BootAgentLifecycles.configure(
                    runtime, config, local, actors, agentDirectory, Clock.systemUTC()
            );
            sceneService.attachLifecycles(sceneLifecycles);
            RemoteServiceRegistry registry = new RemoteServiceRegistry(
                    local.id(),
                    gateway,
                    directory,
                    config.registrySubscriptionLeaseTtl()
            );
            BootRegistryRecovery.configure(runtime, config, registry);
            ClusterNode node = runtime.add("clusterNode", new ClusterNode(registry, local, directory));
            BootAgentMigrationRuntime migrationRuntime = BootAgentMigrationTasks.configureRuntime(
                    runtime, config, Clock.systemUTC()
            );
            configureSceneMigration(
                    runtime,
                    migrationRuntime,
                    sceneService,
                    sceneLifecycles,
                    agentDirectory,
                    gateway,
                    directory,
                    config,
                    Clock.systemUTC()
            );
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
            LocalProfileCache sceneProfileCache = new LocalProfileCache(
                    BootProfileProjectionStore.configure(runtime, config)
            );
            ExecutorService profileRepairExecutor = runtime.add("profileRepairExecutor", Executors.newFixedThreadPool(
                    profileRepairWorkers(config),
                    new NamedThreadFactory("common-battle-profile-repair")
            ));
            OwnerEventRepairDispatcher repairDispatcher = BootOwnerEventRepairs.repairDispatcher(config);
            ProfileSnapshotReader profileSnapshotReader = new RemoteProfileSnapshotReader(gateway, Duration.ofSeconds(1));
            RemoteAllianceSnapshotReader allianceSnapshotReader =
                    new RemoteAllianceSnapshotReader(gateway, Duration.ofSeconds(1));
            RemoteFriendSnapshotReader friendSnapshotReader =
                    new RemoteFriendSnapshotReader(gateway, Duration.ofSeconds(1));
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
                    allianceEventActor,
                    OwnerEventInterestControl.noop(),
                    allianceSnapshotReader
            );
            Clock eventInboxClock = Clock.systemUTC();
            var eventInbox = BootEventInbox.configure(runtime, config);
            var eventInboxRetryPolicy = BootEventInbox.retryPolicy(config);
            SceneFriendAwarenessAgent friendAwareness = new SceneFriendAwarenessAgent(
                    profileMessagePort,
                    friendEventActor,
                    OwnerEventInterestControl.noop(),
                    friendSnapshotReader
            );
            ActorMailboxEventSubscriber profileEventSubscriber = new ActorMailboxEventSubscriber(
                    profileMessagePort,
                    profileActor,
                    (context, event) -> {
                        if (!(event instanceof ProfileChangedEvent profileEvent)) {
                            throw new IllegalArgumentException("event must be ProfileChangedEvent");
                        }
                        profileAwareness.handleProfileChanged(profileEvent);
                    },
                    ProfileChangedEvent.TOPIC,
                    eventInbox,
                    eventInboxRetryPolicy,
                    eventInboxClock
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
            OwnerEventInterestControl profileRepairControl = BootOwnerEventRepairs.repairControl(
                    runtime,
                    config,
                    repairDispatcher,
                    "profileEventRepairs",
                    ProfileChangedEvent.TOPIC,
                    profileEventInterests
            );
            profileEventSubscriber.onRejectedEvent(event ->
                    profileRepairControl.requestRepairOwner(event.ownerKey()));
            profileInterests.attach(profileRepairControl, profileEventInterests);
            ActorMailboxEventSubscriber domainEventSubscriber = new ActorMailboxEventSubscriber(
                    profileMessagePort,
                    domainEventActor,
                    (context, event) -> {
                        if (!(event instanceof PlayerDomainVersionedEvent playerEvent)) {
                            throw new IllegalArgumentException("event must be PlayerDomainVersionedEvent");
                        }
                        domainAwareness.handlePlayerDomainEvent(playerEvent);
                    },
                    PlayerDomainVersionedEvent.TOPIC,
                    eventInbox,
                    eventInboxRetryPolicy,
                    eventInboxClock
            );
            runtime.observe("domainActorEventSubscriber", domainEventSubscriber);
            OwnerActorEventSubscription domainEventInterests = runtime.add("domainEventInterests",
                    new OwnerActorEventSubscription(
                            eventBus,
                            PlayerDomainVersionedEvent.TOPIC,
                            domainEventSubscriber,
                            domainAwareness.processor()::revisionOf,
                            new EventReplaySnapshotRepairer().register(
                                    PlayerDomainVersionedEvent.TOPIC,
                                    new ScenePlayerDomainSnapshotRepairer(
                                            new RemotePlayerDomainProjectionSnapshotReader(gateway, Duration.ofSeconds(1)),
                                            domainAwareness
                                    )
                            ),
                            profileRepairExecutor
                    ));
            OwnerEventInterestControl domainRepairControl = BootOwnerEventRepairs.repairControl(
                    runtime,
                    config,
                    repairDispatcher,
                    "domainEventRepairs",
                    PlayerDomainVersionedEvent.TOPIC,
                    domainEventInterests
            );
            domainEventSubscriber.onRejectedEvent(event ->
                    domainRepairControl.requestRepairOwner(event.ownerKey()));
            domainAwareness.attachInterests(domainRepairControl);
            ActorMailboxEventSubscriber allianceEventSubscriber = new ActorMailboxEventSubscriber(
                    profileMessagePort,
                    allianceEventActor,
                    (context, event) -> {
                        if (!(event instanceof AllianceMemberChangedEvent allianceEvent)) {
                            throw new IllegalArgumentException("event must be AllianceMemberChangedEvent");
                        }
                        allianceAwareness.handleAllianceChanged(allianceEvent);
                    },
                    AllianceMemberChangedEvent.TOPIC,
                    eventInbox,
                    eventInboxRetryPolicy,
                    eventInboxClock
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
                                            allianceSnapshotReader,
                                            allianceAwareness
                                    )
                            ),
                            profileRepairExecutor
                    ));
            OwnerEventInterestControl allianceRepairControl = BootOwnerEventRepairs.repairControl(
                    runtime,
                    config,
                    repairDispatcher,
                    "allianceEventRepairs",
                    AllianceMemberChangedEvent.TOPIC,
                    allianceEventInterests
            );
            allianceEventSubscriber.onRejectedEvent(event ->
                    allianceRepairControl.requestRepairOwner(event.ownerKey()));
            allianceAwareness.attachInterests(allianceRepairControl);
            ActorMailboxEventSubscriber friendEventSubscriber = new ActorMailboxEventSubscriber(
                    profileMessagePort,
                    friendEventActor,
                    (context, event) -> {
                        if (!(event instanceof FriendChangedEvent friendEvent)) {
                            throw new IllegalArgumentException("event must be FriendChangedEvent");
                        }
                        friendAwareness.handleFriendChanged(friendEvent);
                    },
                    FriendChangedEvent.TOPIC,
                    eventInbox,
                    eventInboxRetryPolicy,
                    eventInboxClock
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
                                            friendSnapshotReader,
                                            friendAwareness
                                    )
                            ),
                            profileRepairExecutor
                    ));
            OwnerEventInterestControl friendRepairControl = BootOwnerEventRepairs.repairControl(
                    runtime,
                    config,
                    repairDispatcher,
                    "friendEventRepairs",
                    FriendChangedEvent.TOPIC,
                    friendEventInterests
            );
            friendEventSubscriber.onRejectedEvent(event ->
                    friendRepairControl.requestRepairOwner(event.ownerKey()));
            friendAwareness.attachInterests(friendRepairControl);
            var eventInboxReplayScheduler = BootEventInbox.startReplayScheduler(runtime, config, List.of(
                    profileEventSubscriber,
                    domainEventSubscriber,
                    allianceEventSubscriber,
                    friendEventSubscriber
            ));
            BootOwnerEventRepairs.startRepairDispatcher(runtime, repairDispatcher);
            OwnerActorEventSubscriptionRecoveryScheduler ownerEventRecovery = runtime.add(
                    "ownerEventRecovery",
                    new OwnerActorEventSubscriptionRecoveryScheduler(
                            List.of(
                                    profileEventInterests,
                                    domainEventInterests,
                                    allianceEventInterests,
                                    friendEventInterests
                            ),
                            config.eventSubscriptionRecoveryInterval()
                    )
            );
            ownerEventRecovery.start();
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
            InboundAdmissionController sceneAdmissions = sceneAdmissions(runtime, actors, config);
            runtime.observe("sceneMailboxPressure", sceneAdmissions);
            SceneMailboxPressureGate scenePressureGate = new SceneMailboxPressureGate(activeSceneService, sceneAdmissions);
            startLargeSceneShardTicks(config, sceneService, actorSchedules);
            startLargeSceneShardTransferRepairs(config, sceneService, actorSchedules);
            gateway.handle(SceneOperations.ENTER, (request, responder) -> {
                EnterSceneRequest payload = (EnterSceneRequest) request.payload();
                ownerRouter.route(
                        activeSceneService.routingIdentity(payload.sceneId()),
                        SceneOperations.ENTER,
                        payload,
                        EnterSceneResult.class,
                        responder,
                        ownerFailure -> {
                            SceneMailboxPressureGate.SceneAdmission admission = scenePressureGate.admitEnter(
                                    payload.sceneId(),
                                    payload.chunkX(),
                                    payload.chunkY()
                            );
                            if (!admission.accepted()) {
                                responder.failure(new RpcStructuredException(
                                        admission.decision().reason(),
                                        admission.decision().reason(),
                                        admission.decision().retryAfter()
                                ));
                                return;
                            }
                            activeSceneService.enterAsync(
                                    payload.playerId(),
                                    payload.sceneId(),
                                    payload.chunkX(),
                                    payload.chunkY(),
                                    placement -> responder.success(new EnterSceneResult(
                                            payload.playerId(),
                                            placement.sceneId(),
                                            placement.shardIndex()
                                    )),
                                    ownerFailure
                            );
                        }
                );
            });
            gateway.handle(SceneOperations.LEAVE, (request, responder) -> {
                LeaveSceneRequest payload = (LeaveSceneRequest) request.payload();
                ownerRouter.route(
                        activeSceneService.routingIdentity(payload.sceneId()),
                        SceneOperations.LEAVE,
                        payload,
                        LeaveSceneResult.class,
                        responder,
                        ownerFailure -> activeSceneService.leaveAsync(
                                payload.playerId(),
                                payload.sceneId(),
                                left -> responder.success(new LeaveSceneResult(
                                        payload.playerId(), payload.sceneId(), left
                                )),
                                ownerFailure
                        )
                );
            });
            gateway.handle(SceneOperations.MOVE, (request, responder) -> {
                MoveSceneRequest payload = (MoveSceneRequest) request.payload();
                ownerRouter.route(
                        activeSceneService.routingIdentity(payload.sceneId()),
                        SceneOperations.MOVE,
                        payload,
                        MoveSceneResult.class,
                        responder,
                        ownerFailure -> {
                            SceneMailboxPressureGate.SceneAdmission admission = scenePressureGate.admitMove(
                                    payload.sceneId(),
                                    payload.chunkX(),
                                    payload.chunkY()
                            );
                            if (!admission.accepted()) {
                                responder.failure(new RpcStructuredException(
                                        admission.decision().reason(),
                                        admission.decision().reason(),
                                        admission.decision().retryAfter()
                                ));
                                return;
                            }
                            activeSceneService.moveAsync(
                                    payload.playerId(),
                                    payload.sceneId(),
                                    payload.chunkX(),
                                    payload.chunkY(),
                                    outcome -> responder.success(new MoveSceneResult(
                                            payload.playerId(),
                                            outcome.sceneId(),
                                            outcome.chunkX(),
                                            outcome.chunkY(),
                                            outcome.previousShardIndex(),
                                            outcome.shardIndex(),
                                            outcome.moved(),
                                            outcome.aoi().visibleEnter(),
                                            outcome.aoi().visibleLeave(),
                                            outcome.aoi().observerEnter(),
                                            outcome.aoi().observerLeave()
                                    )),
                                    ownerFailure
                            );
                        }
                );
            });
            gateway.handle(SceneOperations.COMBAT_DAMAGE, (request, responder) -> {
                CombatDamageRequest payload = (CombatDamageRequest) request.payload();
                ownerRouter.route(
                        activeSceneService.routingIdentity(payload.sceneId()),
                        SceneOperations.COMBAT_DAMAGE,
                        payload,
                        CombatDamageResult.class,
                        responder,
                        ownerFailure -> {
                            if (sceneService instanceof LargeSceneShardService largeScene
                                    && largeScene.sceneId().equals(payload.sceneId())) {
                                largeScene.applyDamageAsync(
                                        payload.attackerId(),
                                        payload.targetId(),
                                        payload.damage(),
                                        outcome -> responder.success(new CombatDamageResult(
                                                outcome.sceneId(),
                                                outcome.attackerId(),
                                                outcome.targetId(),
                                                outcome.shardIndex(),
                                                outcome.damage(),
                                                outcome.hpBefore(),
                                                outcome.hpAfter(),
                                                outcome.applied()
                                        )),
                                        ownerFailure
                                );
                                return;
                            }
                            SceneCombatDamageOutcome outcome = SceneCombatDamageOutcome.rejected(
                                    payload.sceneId(),
                                    payload.attackerId(),
                                    payload.targetId()
                            );
                            responder.success(new CombatDamageResult(
                                    outcome.sceneId(),
                                    outcome.attackerId(),
                                    outcome.targetId(),
                                    outcome.shardIndex(),
                                    outcome.damage(),
                                    outcome.hpBefore(),
                                    outcome.hpAfter(),
                                    outcome.applied()
                            ));
                        }
                );
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
            ActorMailboxEventSubscriber configEventSubscriber = new ActorMailboxEventSubscriber(
                    profileMessagePort,
                    actors.actor("scene-config-events:" + local.id().node()),
                    (context, event) -> configCache.onEvent(event),
                    GameConfigChangedEvent.TOPIC,
                    eventInbox,
                    eventInboxRetryPolicy,
                    eventInboxClock
            );
            runtime.observe("configActorEventSubscriber", configEventSubscriber);
            if (eventInboxReplayScheduler != null) {
                eventInboxReplayScheduler.register(configEventSubscriber);
            }
            eventSubscriptions.register(
                    GameConfigChangedEvent.TOPIC,
                    configEventSubscriber,
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
            runtime.add("opsHttp", BootOpsHttp.start(config, local, actors, directory, runtime));
            System.out.println("Scene server started: " + local.id().wireName()
                    + ", config=" + configCache.active().version()
                    + ", ops=" + config.opsEndpoint().host() + ":" + config.opsEndpoint().port());
            new CountDownLatch(1).await();
        } catch (Exception e) {
            runtime.closeSuppressing(e);
            throw e;
        } catch (Error e) {
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
                    config.sceneShards(),
                    config.sceneShardChunkSize(),
                    Clock.systemUTC()
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

    private static void startLargeSceneShardTicks(
            ClusterNodeConfig config,
            SceneServiceStrategy sceneService,
            ActorScheduleRegistry schedules
    ) {
        if (config.sceneTickEnabled() && sceneService instanceof LargeSceneShardService largeScene) {
            largeScene.scheduleShardTicks(
                    schedules,
                    config.sceneTickInitialDelay(),
                    config.sceneTickInterval(),
                    ignored -> {
                    }
            );
        }
    }

    private static void configureSceneMigration(
            BootRuntime runtime,
            BootAgentMigrationRuntime migrationRuntime,
            SceneServiceStrategy sceneService,
            AgentLifecycleManager lifecycles,
            RemoteAgentDirectory agentDirectory,
            ClusterRpcGateway gateway,
            ClusterDirectory directory,
            ClusterNodeConfig config,
            Clock clock
    ) {
        if (!(sceneService instanceof MultiSmallSceneService smallScenes)) {
            // 大场景 shard 是单进程内并行边界；未实现整场景协调迁移前不发布单 shard 热迁移能力。
            return;
        }
        ProtoSmallSceneAgentSnapshotSerializer serializer = new ProtoSmallSceneAgentSnapshotSerializer();
        AgentMigrationStatePacker packer = new SmallSceneAgentMigrationStatePacker(smallScenes, serializer);
        AgentMigrationSourceHook sourceHook = new SmallSceneAgentMigrationSourceHook(smallScenes, serializer);
        AgentMigrationRestoreHandler restoreHandler = new SmallSceneAgentMigrationRestoreHandler(
                smallScenes, serializer
        );
        AgentMigrationExecutor executor = runtime.add(
                "sceneAgentMigrationExecutor",
                new AgentMigrationExecutor(
                        "scene-agent-migration-" + config.node(),
                        Math.max(1, Math.min(2, config.actorWorkers())),
                        Math.max(64, config.actorWorkers() * 64)
                )
        );
        RemoteAgentMigrationClient client = new RemoteAgentMigrationClient(gateway);
        AgentMigrationCoordinator coordinator = migrationRuntime.coordinator(
                lifecycles, agentDirectory, client, executor, clock, sourceHook
        );
        runtime.observe("sceneAgentMigrationCoordinator", coordinator);
        BootAgentMigrationTasks.configureRecovery(
                runtime,
                config,
                migrationRuntime.recoveryService(
                        agentDirectory,
                        lifecycles,
                        client,
                        executor,
                        clock,
                        config.serviceId().wireName()
                ),
                ignored -> {
                }
        );
        new AgentMigrationTargetEndpoint(
                lifecycles,
                restoreHandler,
                BootAgentMigrationTargetReceipts.configure(runtime, config, clock)
        ).bind(gateway);
        ActorHotspotMigrationPlanner planner = new ActorHotspotMigrationPlanner(
                agentDirectory,
                directory,
                migrationRuntime.taskStore(),
                coordinator::migrate,
                sceneService::identityOfActor,
                (sourceActorId, identity, target) ->
                        sceneService.migrationTargetActorId(identity, target.id())
        );
        runtime.observe("sceneHotspotMigrations", new ActorHotspotMigrationSubmitService(planner, packer));
    }

    private static void startLargeSceneShardTransferRepairs(
            ClusterNodeConfig config,
            SceneServiceStrategy sceneService,
            ActorScheduleRegistry schedules
    ) {
        if (config.sceneShardTransferRepairEnabled() && sceneService instanceof LargeSceneShardService largeScene) {
            largeScene.scheduleShardTransferRepairs(
                    schedules,
                    config.sceneShardTransferRepairInitialDelay(),
                    config.sceneShardTransferRepairInterval(),
                    config.sceneShardTransferRepairTimeout()
            );
        }
    }

    private static InboundAdmissionController sceneAdmissions(
            BootRuntime runtime,
            ActorSystem actors,
            ClusterNodeConfig config
    ) {
        ActorMailboxPressureAdmissionController pressure = new ActorMailboxPressureAdmissionController(
                (target, operation) -> AdmissionDecision.accept(),
                actors,
                config.sceneMailboxPressurePolicy(),
                SceneMailboxPressureGate::actorIdOf
        );
        runtime.observe("sceneMailboxPressure", pressure);
        ActorHotspotAdmissionController hotspot = new ActorHotspotAdmissionController(
                pressure,
                actors,
                runtime.healthRegistry().actorSlowTasks(),
                config.actorHotspotPolicy(),
                config.actorHotspotRetryAfter(),
                SceneMailboxPressureGate::actorIdOf
        );
        runtime.observe("sceneHotspotAdmission", hotspot);
        return hotspot;
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
