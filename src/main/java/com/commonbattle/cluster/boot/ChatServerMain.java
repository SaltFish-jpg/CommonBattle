package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.migration.AgentMigrationPayloadCodecs;
import com.commonbattle.actor.agent.remote.AgentDirectoryPayloadCodecs;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterNode;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.event.ClusterEventPayloadCodecs;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.registry.RegistryPayloadCodecs;
import com.commonbattle.cluster.registry.RemoteServiceRegistry;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.cross.CrossPayloadCodecs;
import com.commonbattle.game.chat.ChatChannelEndpoint;
import com.commonbattle.game.chat.ChatChannelManager;
import com.commonbattle.game.chat.ChatAccessControl;
import com.commonbattle.game.chat.ChatPayloadCodecs;
import com.commonbattle.game.chat.DirectChatSessionManager;
import com.commonbattle.game.chat.AccessControlledChatMessagePolicy;
import com.commonbattle.game.chat.PlayerOutboundChatDeliverySink;
import com.commonbattle.game.chat.ProfileAwareChatMessagePolicy;
import com.commonbattle.game.chat.RoutedChatEndpoint;
import com.commonbattle.game.chat.RoutedChatService;
import com.commonbattle.game.player.PlayerBusinessCommandPayloadCodecs;
import com.commonbattle.game.player.event.PlayerDomainEventProcessor;
import com.commonbattle.game.profile.LocalProfileCache;
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.profile.ProfileRuntime;
import com.commonbattle.game.profile.RemoteProfileSnapshotReader;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.scene.SceneFriendAwarenessAgent;
import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.scene.ScenePlayerInterestCoordinator;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerDeliveryOverflowStrategy;
import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.shop.ShopStockPayloadCodecs;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

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
            RemoteServiceRegistry registry = new RemoteServiceRegistry(
                    local.id(),
                    gateway,
                    directory,
                    config.registrySubscriptionLeaseTtl()
            );
            BootRegistryRecovery.configure(runtime, config, registry);
            ClusterNode node = runtime.add("clusterNode", new ClusterNode(registry, local, directory));
            ActorSystem actors = runtime.add("actors", new ActorSystem(config.actorSystemConfig()));
            DefaultAgentMessagePort messages = runtime.add("messages", new DefaultAgentMessagePort(actors, gateway));
            ProfileRuntime profiles = new ProfileRuntime(
                    new LocalProfileCache(),
                    ProfileInterestControl.noop(),
                    new RemoteProfileSnapshotReader(gateway, Duration.ofSeconds(1))
            );
            ScenePlayerInterestCoordinator interests = new ScenePlayerInterestCoordinator(
                    new SceneProfileAwarenessAgent(messages, actors.actor("chat-profile-awareness"), profiles),
                    new SceneFriendAwarenessAgent(messages, actors.actor("chat-friend-awareness")),
                    new ScenePlayerDomainEventAgent(
                            messages,
                            actors.actor("chat-domain-awareness"),
                            new PlayerDomainEventProcessor()
                    ),
                    new SceneAllianceAwarenessAgent(messages, actors.actor("chat-alliance-awareness"))
            );
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
            ChatChannelManager channels = new ChatChannelManager(
                    actors,
                    messages,
                    interests,
                    messagePolicy,
                    deliverySink,
                    clock,
                    config.chatRouteConfig().maxHistoryMessages()
            );
            DirectChatSessionManager directSessions = new DirectChatSessionManager(
                    actors,
                    messages,
                    messagePolicy,
                    deliverySink,
                    clock,
                    config.chatRouteConfig().maxHistoryMessages()
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
}
