package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.backpressure.ActorHotspotAdmissionController;
import com.commonbattle.actor.backpressure.ActorMailboxPressureAdmissionController;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.backpressure.InboundAdmissionController;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterNode;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.event.ClusterEventSubscriptionLeaseRenewer;
import com.commonbattle.cluster.event.ClusterVersionedEventBus;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.registry.RemoteServiceRegistry;
import com.commonbattle.cluster.rpc.ClusterRpcDeliveryFailureMapper;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.actor.message.ExecutorAskTimeoutScheduler;
import com.commonbattle.game.chat.ChatChannelEndpoint;
import com.commonbattle.game.chat.ChatActorIds;
import com.commonbattle.game.chat.ChatChannelManager;
import com.commonbattle.game.chat.ChatAccessControl;
import com.commonbattle.game.chat.DirectChatSessionManager;
import com.commonbattle.game.chat.AccessControlledChatMessagePolicy;
import com.commonbattle.game.chat.ChatAllianceMembershipProjector;
import com.commonbattle.game.chat.AllianceAwareChatChannelAccessPolicy;
import com.commonbattle.game.chat.FriendAwareDirectChatAccessPolicy;
import com.commonbattle.game.chat.PlayerOutboundChatDeliverySink;
import com.commonbattle.game.chat.ProfileAwareChatMessagePolicy;
import com.commonbattle.game.chat.RoutedChatEndpoint;
import com.commonbattle.game.chat.RoutedChatService;
import com.commonbattle.game.event.ActorMailboxEventSubscriber;
import com.commonbattle.game.event.OwnerActorEventSubscription;
import com.commonbattle.game.event.OwnerActorEventSubscriptionRecoveryScheduler;
import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.event.OwnerEventRepairDispatcher;
import com.commonbattle.game.player.event.PlayerDomainEventProcessor;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.player.event.RemotePlayerDomainProjectionSnapshotReader;
import com.commonbattle.game.player.event.ScenePlayerDomainSnapshotRepairer;
import com.commonbattle.game.profile.LocalProfileCache;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileEventReplayRepairer;
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.profile.ProfileOwnerEventInterests;
import com.commonbattle.game.profile.ProfileOwnerKeyParser;
import com.commonbattle.game.profile.ProfileRuntime;
import com.commonbattle.game.profile.RemoteProfileSnapshotReader;
import com.commonbattle.game.profile.SceneProfileSnapshotRepairer;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.scene.SceneFriendAwarenessAgent;
import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.scene.ScenePlayerInterestCoordinator;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import com.commonbattle.game.snapshot.EventReplaySnapshotRepairer;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerDeliveryOverflowStrategy;
import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
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

/**
 * Chat 服启动入口。
 * 聊天频道和私聊会话按业务实体 Actor 承载，网络线程只负责把 RPC 投递进对应 mailbox。
 */
public final class ChatServerMain {
    private ChatServerMain() {
    }

    public static void main(String[] args) throws Exception {
        BootRuntime runtime = new BootRuntime().installShutdownHook();
        try {
            ClusterNodeConfig config = ClusterNodeConfig.load(args, "cluster/chat.properties");
            config.validate(ServiceKind.CHAT).throwIfInvalid();
            ServiceDescriptor local = ClusterDescriptors.fromConfig(config);
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
            ActorSystem actors = BootActors.configure(runtime, config, Clock.systemUTC());
            DefaultAgentMessagePort messages = runtime.add("messages", new DefaultAgentMessagePort(
                    actors,
                    gateway,
                    new ExecutorAskTimeoutScheduler(),
                    new ClusterRpcDeliveryFailureMapper()
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
            LocalProfileCache profileCache = new LocalProfileCache();
            ProfileOwnerEventInterests profileInterests = new ProfileOwnerEventInterests();
            runtime.observe("profileInterests", profileInterests);
            RemoteProfileSnapshotReader profileSnapshotReader = new RemoteProfileSnapshotReader(gateway, Duration.ofSeconds(1));
            ProfileRuntime profiles = new ProfileRuntime(profileCache, profileInterests, profileSnapshotReader);
            runtime.observe("profileRuntime", profiles);
            SceneProfileAwarenessAgent profileAwareness = new SceneProfileAwarenessAgent(
                    messages,
                    actors.actor("chat-profile-awareness"),
                    profiles
            );
            SceneFriendAwarenessAgent friendAwareness = new SceneFriendAwarenessAgent(
                    messages,
                    actors.actor("chat-friend-awareness")
            );
            ScenePlayerDomainEventAgent domainAwareness = new ScenePlayerDomainEventAgent(
                    messages,
                    actors.actor("chat-domain-awareness"),
                    new PlayerDomainEventProcessor()
            );
            SceneAllianceAwarenessAgent allianceAwareness = new SceneAllianceAwarenessAgent(
                    messages,
                    actors.actor("chat-alliance-awareness")
            );
            ScenePlayerInterestCoordinator interests = new ScenePlayerInterestCoordinator(
                    profileAwareness,
                    friendAwareness,
                    domainAwareness,
                    allianceAwareness
            );
            runtime.observe("sceneRuntime", interests);
            ExecutorService eventRepairExecutor = runtime.add("eventRepairExecutor", Executors.newFixedThreadPool(
                    Math.max(1, Math.min(2, config.actorWorkers())),
                    runnable -> {
                        Thread thread = new Thread(runnable, "common-battle-chat-event-repair");
                        thread.setDaemon(true);
                        return thread;
                    }
            ));
            OwnerEventRepairDispatcher repairDispatcher = BootOwnerEventRepairs.repairDispatcher(config);
            ActorMailboxEventSubscriber profileEventSubscriber = new ActorMailboxEventSubscriber(
                    messages,
                    actors.actor("chat-profile-awareness"),
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
                                    .map(profileCache::revisionOf)
                                    .findFirst()
                                    .orElse(0L),
                            new ProfileEventReplayRepairer(
                                    new SceneProfileSnapshotRepairer(profileSnapshotReader, profileAwareness)),
                            eventRepairExecutor
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
                    messages,
                    actors.actor("chat-domain-awareness"),
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
                            new EventReplaySnapshotRepairer().register(
                                    PlayerDomainVersionedEvent.TOPIC,
                                    new ScenePlayerDomainSnapshotRepairer(
                                            new RemotePlayerDomainProjectionSnapshotReader(gateway, Duration.ofSeconds(1)),
                                            domainAwareness
                                    )
                            ),
                            eventRepairExecutor
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
                    messages,
                    actors.actor("chat-alliance-awareness"),
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
                            eventRepairExecutor
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
                    messages,
                    actors.actor("chat-friend-awareness"),
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
                            eventRepairExecutor
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
            ChatAccessControl accessControl = new ChatAccessControl();
            Clock clock = Clock.systemUTC();
            PlayerOutboundDeliveryHub deliveryHub = new PlayerOutboundDeliveryHub(
                    new InMemoryPlayerSessionRegistry(clock),
                    clock,
                    config.chatRouteConfig().maxPendingDeliveriesPerRecipient(),
                    PlayerDeliveryOverflowStrategy.valueOf(config.chatRouteConfig().deliveryOverflowStrategy().name())
            );
            runtime.observe("playerOutboundDeliveryHub", deliveryHub);
            PlayerOutboundChatDeliverySink deliverySink = new PlayerOutboundChatDeliverySink(deliveryHub);
            ProfileAwareChatMessagePolicy profilePolicy = new ProfileAwareChatMessagePolicy(profiles);
            AccessControlledChatMessagePolicy messagePolicy = new AccessControlledChatMessagePolicy(accessControl, profilePolicy);
            InboundAdmissionController chatAdmissions = chatAdmissions(runtime, actors, config);
            runtime.observe("chatMailboxPressure", chatAdmissions);
            ChatChannelManager channels = new ChatChannelManager(
                    actors,
                    messages,
                    interests,
                    new AllianceAwareChatChannelAccessPolicy(allianceAwareness),
                    messagePolicy,
                    deliverySink,
                    clock,
                    config.chatRouteConfig().maxHistoryMessages(),
                    chatAdmissions
            );
            allianceAwareness.attachMembershipChanges(new ChatAllianceMembershipProjector(channels));
            DirectChatSessionManager directSessions = new DirectChatSessionManager(
                    actors,
                    messages,
                    new FriendAwareDirectChatAccessPolicy(friendAwareness),
                    messagePolicy,
                    deliverySink,
                    clock,
                    config.chatRouteConfig().maxHistoryMessages(),
                    chatAdmissions
            );
            RoutedChatService routedChat = new RoutedChatService(channels, directSessions, config.chatRouteConfig(), accessControl);
            new ChatChannelEndpoint(channels).bind(gateway);
            new RoutedChatEndpoint(routedChat).bind(gateway);
            runtime.observe("chat", routedChat);
            node.start(
                    List.of(ServiceKind.GAME, ServiceKind.SCENE, ServiceKind.PROXY, ServiceKind.REGION),
                    config.registryLeaseTtl(),
                    config.registryHeartbeatInterval()
            );
            runtime.add("opsHttp", BootOpsHttp.start(config, local, actors, directory, runtime.healthRegistry()));
            System.out.println("Chat server started: " + local.id().wireName()
                    + ", ops=" + config.opsEndpoint().host() + ":" + config.opsEndpoint().port());
            new CountDownLatch(1).await();
        } catch (Exception e) {
            runtime.closeSuppressing(e);
            throw e;
        }
    }

    private static InboundAdmissionController chatAdmissions(
            BootRuntime runtime,
            ActorSystem actors,
            ClusterNodeConfig config
    ) {
        ActorMailboxPressureAdmissionController pressure = new ActorMailboxPressureAdmissionController(
                (target, operation) -> AdmissionDecision.accept(),
                actors,
                config.chatMailboxPressurePolicy(),
                ChatActorIds::actorIdOf
        );
        runtime.observe("chatMailboxPressure", pressure);
        ActorHotspotAdmissionController hotspot = new ActorHotspotAdmissionController(
                pressure,
                actors,
                runtime.healthRegistry().actorSlowTasks(),
                config.actorHotspotPolicy(),
                config.actorHotspotRetryAfter(),
                ChatActorIds::actorIdOf
        );
        runtime.observe("chatHotspotAdmission", hotspot);
        return hotspot;
    }

}
