package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.agent.remote.RemoteAgentDirectory;
import com.commonbattle.actor.backpressure.AdmissionControlledAgentRouter;
import com.commonbattle.actor.backpressure.AgentRateLimitPolicy;
import com.commonbattle.actor.backpressure.TokenBucketAgentAdmissionController;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.PlayerGrayRoutePolicy;
import com.commonbattle.cluster.rpc.RoutedRpcGateway;
import com.commonbattle.cluster.rpc.RpcRoutePolicyView;
import com.commonbattle.game.agent.BusinessAgentHandlerRegistry;
import com.commonbattle.game.agent.BusinessAgentRpcEndpoint;
import com.commonbattle.game.agent.DefaultBusinessAgentMessagePort;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.player.PlayerBusinessCommandBinder;
import com.commonbattle.game.player.PlayerBusinessCommandEndpoint;
import com.commonbattle.game.player.PlayerBusinessCommandGateway;
import com.commonbattle.game.player.PlayerBusinessCommandHandler;
import com.commonbattle.game.player.PlayerBusinessResponseHub;
import com.commonbattle.game.player.PlayerClientCommandIngress;
import com.commonbattle.game.player.PlayerAgentDrainService;
import com.commonbattle.game.player.PlayerAutoSaveScheduler;
import com.commonbattle.game.player.PlayerGameAgentManager;
import com.commonbattle.game.player.PlayerGatewayConfig;
import com.commonbattle.game.player.PlayerStateRepository;
import com.commonbattle.game.profile.PlayerProfileEventProjector;
import com.commonbattle.game.profile.PlayerProfileSnapshotProjector;
import com.commonbattle.game.profile.FriendProfileEventProjector;
import com.commonbattle.game.profile.ProfileSnapshotRepository;
import com.commonbattle.game.session.InMemoryPlayerCommandAuditLog;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerClientConnectionService;
import com.commonbattle.game.session.PlayerDeliveryOverflowStrategy;
import com.commonbattle.game.session.PlayerLoginService;
import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.PlayerSessionRegistry;
import com.commonbattle.game.social.AllianceAgentManager;
import com.commonbattle.game.social.FriendAgentManager;
import com.commonbattle.game.social.InMemoryAllianceSnapshotRepository;
import com.commonbattle.game.social.FriendSnapshotRepository;
import com.commonbattle.game.social.InMemoryFriendSnapshotRepository;
import com.commonbattle.game.social.SocialAgentOperationBinder;
import com.commonbattle.game.shop.ShopStockAsyncClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Game 服玩家入口启动装配。
 * 玩家命令链路的组件在这里集中创建，保证登录、生命周期路由、业务 Agent 和可靠事件发布器使用同一组依赖。
 */
record BootGamePlayerRuntime(
        DefaultAgentMessagePort messages,
        AgentLifecycleManager lifecycles,
        PlayerStateRepository stateRepository,
        PlayerGameAgentManager agents,
        FriendAgentManager friendAgents,
        AllianceAgentManager allianceAgents,
        PlayerSessionRegistry sessions,
        PlayerLoginService logins,
        PlayerOutboundDeliveryHub outbound,
        PlayerClientConnectionService clientConnections,
        PlayerClientCommandIngress clientCommandIngress,
        PlayerCommandDispatcher dispatcher,
        PlayerBusinessResponseHub businessResponses,
        PlayerBusinessCommandGateway businessCommands,
        BusinessAgentHandlerRegistry businessAgentHandlers,
        DefaultBusinessAgentMessagePort businessMessages,
        InMemoryPlayerCommandAuditLog audit,
        PlayerAutoSaveScheduler autoSaves,
        PlayerAgentDrainService drain
) {
    static BootGamePlayerRuntime configure(
            BootRuntime runtime,
            ClusterNodeConfig config,
            ServiceDescriptor local,
            ActorSystem actors,
            ClusterRpcGateway gateway,
            LocalGameConfigCache configCache,
            ProfileSnapshotRepository profileSnapshots,
            EventPublisher playerDomainEvents,
            EventPublisher profileEvents,
            Clock clock
    ) {
        return configure(
                runtime,
                config,
                local,
                actors,
                gateway,
                configCache,
                profileSnapshots,
                new InMemoryFriendSnapshotRepository(),
                playerDomainEvents,
                profileEvents,
                playerDomainEvents,
                new com.commonbattle.game.shop.RemoteShopStockAsyncClient(routedPlayerRpc(config, gateway)),
                clock
        );
    }

    static BootGamePlayerRuntime configure(
            BootRuntime runtime,
            ClusterNodeConfig config,
            ServiceDescriptor local,
            ActorSystem actors,
            ClusterRpcGateway gateway,
            LocalGameConfigCache configCache,
            ProfileSnapshotRepository profileSnapshots,
            FriendSnapshotRepository friendSnapshots,
            EventPublisher playerDomainEvents,
            EventPublisher profileEvents,
            EventPublisher friendEvents,
            Clock clock
    ) {
        return configure(
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
                new com.commonbattle.game.shop.RemoteShopStockAsyncClient(routedPlayerRpc(config, gateway)),
                clock
        );
    }

    static BootGamePlayerRuntime configure(
            BootRuntime runtime,
            ClusterNodeConfig config,
            ServiceDescriptor local,
            ActorSystem actors,
            ClusterRpcGateway gateway,
            LocalGameConfigCache configCache,
            ProfileSnapshotRepository profileSnapshots,
            FriendSnapshotRepository friendSnapshots,
            EventPublisher playerDomainEvents,
            EventPublisher profileEvents,
            EventPublisher friendEvents,
            ShopStockAsyncClient shopStockAsyncClient,
            Clock clock
    ) {
        return configure(
                runtime,
                local,
                actors,
                routedPlayerRpc(config, gateway),
                new RemoteAgentDirectory(gateway),
                BootPlayerStateRepository.configure(runtime, config),
                configCache,
                profileSnapshots,
                friendSnapshots,
                playerDomainEvents,
                profileEvents,
                friendEvents,
                shopStockAsyncClient,
                clock,
                config.gameServerOpenTime(),
                config.playerCommandRateLimitPolicy(),
                config.playerAutoSaveEnabled(),
                config.playerAutoSaveInitialDelay(),
                config.playerAutoSaveInterval(),
                config.playerBusinessResponseTimeout(),
                config.playerGatewayConfig().maxPendingAckMessages()
        );
    }

    static BootGamePlayerRuntime configure(
            BootRuntime runtime,
            ServiceDescriptor local,
            ActorSystem actors,
            RpcGateway rpc,
            AgentDirectory agentDirectory,
            PlayerStateRepository stateRepository,
            LocalGameConfigCache configCache,
            ProfileSnapshotRepository profileSnapshots,
            EventPublisher playerDomainEvents,
            EventPublisher profileEvents,
            Clock clock,
            Instant serverOpenTime,
            AgentRateLimitPolicy commandRateLimitPolicy
    ) {
        return configure(
                runtime,
                local,
                actors,
                rpc,
                agentDirectory,
                stateRepository,
                configCache,
                profileSnapshots,
                new InMemoryFriendSnapshotRepository(),
                playerDomainEvents,
                profileEvents,
                playerDomainEvents,
                null,
                clock,
                serverOpenTime,
                commandRateLimitPolicy
        );
    }

    static BootGamePlayerRuntime configure(
            BootRuntime runtime,
            ServiceDescriptor local,
            ActorSystem actors,
            RpcGateway rpc,
            AgentDirectory agentDirectory,
            PlayerStateRepository stateRepository,
            LocalGameConfigCache configCache,
            ProfileSnapshotRepository profileSnapshots,
            FriendSnapshotRepository friendSnapshots,
            EventPublisher playerDomainEvents,
            EventPublisher profileEvents,
            EventPublisher friendEvents,
            Clock clock,
            Instant serverOpenTime,
            AgentRateLimitPolicy commandRateLimitPolicy
    ) {
        return configure(
                runtime,
                local,
                actors,
                rpc,
                agentDirectory,
                stateRepository,
                configCache,
                profileSnapshots,
                friendSnapshots,
                playerDomainEvents,
                profileEvents,
                friendEvents,
                null,
                clock,
                serverOpenTime,
                commandRateLimitPolicy
        );
    }

    static BootGamePlayerRuntime configure(
            BootRuntime runtime,
            ServiceDescriptor local,
            ActorSystem actors,
            RpcGateway rpc,
            AgentDirectory agentDirectory,
            PlayerStateRepository stateRepository,
            LocalGameConfigCache configCache,
            ProfileSnapshotRepository profileSnapshots,
            FriendSnapshotRepository friendSnapshots,
            EventPublisher playerDomainEvents,
            EventPublisher profileEvents,
            EventPublisher friendEvents,
            ShopStockAsyncClient shopStockAsyncClient,
            Clock clock,
            Instant serverOpenTime,
            AgentRateLimitPolicy commandRateLimitPolicy
    ) {
        return configure(
                runtime,
                local,
                actors,
                rpc,
                agentDirectory,
                stateRepository,
                configCache,
                profileSnapshots,
                friendSnapshots,
                playerDomainEvents,
                profileEvents,
                friendEvents,
                shopStockAsyncClient,
                clock,
                serverOpenTime,
                commandRateLimitPolicy,
                false,
                Duration.ZERO,
                Duration.ofSeconds(60),
                PlayerBusinessCommandGateway.DEFAULT_RESPONSE_TIMEOUT,
                PlayerGatewayConfig.defaults().maxPendingAckMessages()
        );
    }

    static BootGamePlayerRuntime configure(
            BootRuntime runtime,
            ServiceDescriptor local,
            ActorSystem actors,
            RpcGateway rpc,
            AgentDirectory agentDirectory,
            PlayerStateRepository stateRepository,
            LocalGameConfigCache configCache,
            ProfileSnapshotRepository profileSnapshots,
            FriendSnapshotRepository friendSnapshots,
            EventPublisher playerDomainEvents,
            EventPublisher profileEvents,
            EventPublisher friendEvents,
            ShopStockAsyncClient shopStockAsyncClient,
            Clock clock,
            Instant serverOpenTime,
            AgentRateLimitPolicy commandRateLimitPolicy,
            boolean autoSaveEnabled,
            Duration autoSaveInitialDelay,
            Duration autoSaveInterval,
            Duration businessResponseTimeout,
            int maxPendingAckMessagesPerPlayer
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(actors, "actors");
        Objects.requireNonNull(rpc, "rpc");
        Objects.requireNonNull(agentDirectory, "agentDirectory");
        Objects.requireNonNull(stateRepository, "stateRepository");
        Objects.requireNonNull(configCache, "configCache");
        Objects.requireNonNull(profileSnapshots, "profileSnapshots");
        Objects.requireNonNull(friendSnapshots, "friendSnapshots");
        Objects.requireNonNull(playerDomainEvents, "playerDomainEvents");
        Objects.requireNonNull(profileEvents, "profileEvents");
        Objects.requireNonNull(friendEvents, "friendEvents");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(serverOpenTime, "serverOpenTime");
        Objects.requireNonNull(commandRateLimitPolicy, "commandRateLimitPolicy");
        Objects.requireNonNull(autoSaveInitialDelay, "autoSaveInitialDelay");
        Objects.requireNonNull(autoSaveInterval, "autoSaveInterval");
        Objects.requireNonNull(businessResponseTimeout, "businessResponseTimeout");
        if (maxPendingAckMessagesPerPlayer <= 0) {
            throw new IllegalArgumentException("maxPendingAckMessagesPerPlayer must be positive");
        }

        if (rpc instanceof RpcRoutePolicyView routePolicyView) {
            runtime.observe("rpcRoutePolicy", routePolicyView);
        }
        DefaultAgentMessagePort messages = runtime.add("agentMessages", new DefaultAgentMessagePort(actors, rpc));
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(local.id(), actors, agentDirectory, clock);
        FriendAgentManager friendAgents = new FriendAgentManager(
                actors,
                messages,
                friendSnapshots,
                friendEvents,
                new FriendProfileEventProjector(profileSnapshots, profileEvents, clock),
                lifecycles
        );
        AllianceAgentManager allianceAgents = new AllianceAgentManager(
                actors,
                messages,
                new InMemoryAllianceSnapshotRepository(),
                playerDomainEvents,
                lifecycles
        );
        PlayerGameAgentManager agents = new PlayerGameAgentManager(
                actors,
                messages,
                stateRepository,
                configCache,
                lifecycles,
                clock,
                serverOpenTime,
                playerDomainEvents,
                new PlayerProfileEventProjector(profileSnapshots, profileEvents, clock)::onPlayerDomainEvent,
                new PlayerProfileSnapshotProjector(profileSnapshots)::project,
                shopStockAsyncClient
        );
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(clock);
        PlayerLoginService logins = new PlayerLoginService(agents, sessions);
        InMemoryPlayerCommandAuditLog audit = new InMemoryPlayerCommandAuditLog();
        LifecycleAwareAgentRouter lifecycleRouter = new LifecycleAwareAgentRouter(lifecycles, messages);
        PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                sessions,
                new PlayerCommandSequencer(),
                new AdmissionControlledAgentRouter(
                        new TokenBucketAgentAdmissionController(commandRateLimitPolicy, clock),
                        lifecycleRouter
                ),
                audit,
                playerId -> configCache.resolve(playerId).version(),
                clock
        );
        PlayerBusinessResponseHub businessResponses = new PlayerBusinessResponseHub();
        PlayerBusinessCommandGateway businessCommands = runtime.add("playerBusinessCommands", new PlayerBusinessCommandGateway(
                dispatcher,
                rpc,
                businessResponses,
                businessResponseTimeout
        ));
        PlayerOutboundDeliveryHub outbound = new PlayerOutboundDeliveryHub(
                sessions,
                clock,
                PlayerClientConnectionService.DEFAULT_OFFLINE_FLUSH_LIMIT,
                maxPendingAckMessagesPerPlayer,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        PlayerClientConnectionService clientConnections = new PlayerClientConnectionService(logins, outbound);
        PlayerClientCommandIngress clientCommandIngress = new PlayerClientCommandIngress(businessCommands, outbound);
        BusinessAgentHandlerRegistry businessAgentHandlers = new BusinessAgentHandlerRegistry();
        SocialAgentOperationBinder.register(
                businessAgentHandlers,
                friendAgents::getOrCreate,
                allianceAgents::getOrCreate
        );
        DefaultBusinessAgentMessagePort businessMessages = new DefaultBusinessAgentMessagePort(
                messages,
                businessCommands,
                lifecycleRouter,
                businessAgentHandlers
        );
        PlayerBusinessCommandBinder.registerExamples(
                dispatcher,
                new PlayerBusinessCommandHandler(agents::getOrCreate, businessResponses)
        );
        if (rpc instanceof ClusterRpcGateway clusterRpc) {
            new PlayerBusinessCommandEndpoint(businessCommands).bind(clusterRpc);
            new BusinessAgentRpcEndpoint(lifecycleRouter, businessAgentHandlers).bind(clusterRpc);
        } else if (rpc instanceof RoutedRpcGateway routed && routed.delegate() instanceof ClusterRpcGateway clusterRpc) {
            new PlayerBusinessCommandEndpoint(businessCommands).bind(clusterRpc);
            new BusinessAgentRpcEndpoint(lifecycleRouter, businessAgentHandlers).bind(clusterRpc);
        }
        PlayerAutoSaveScheduler autoSaves = null;
        if (autoSaveEnabled) {
            autoSaves = runtime.add("playerAutoSaves",
                    new PlayerAutoSaveScheduler(agents, autoSaveInitialDelay, autoSaveInterval));
            autoSaves.start();
        }
        PlayerAgentDrainService drain = new PlayerAgentDrainService(agents);
        BootGamePlayerRuntime playerRuntime = new BootGamePlayerRuntime(
                messages,
                lifecycles,
                stateRepository,
                agents,
                friendAgents,
                allianceAgents,
                sessions,
                logins,
                outbound,
                clientConnections,
                clientCommandIngress,
                dispatcher,
                businessResponses,
                businessCommands,
                businessAgentHandlers,
                businessMessages,
                audit,
                autoSaves,
                drain
        );
        runtime.observe("agentLifecycles", lifecycles);
        runtime.observe("playerStateRepository", stateRepository);
        runtime.observe("playerAgents", agents);
        runtime.observe("friendAgents", friendAgents);
        runtime.observe("playerSessions", sessions);
        runtime.observe("playerLogins", logins);
        runtime.observe("playerOutboundDeliveries", outbound);
        runtime.observe("playerCommandAudit", audit);
        runtime.observe("playerCommandDispatcher", dispatcher);
        runtime.observe("playerBusinessResponses", businessResponses);
        runtime.observe("playerAgentDrain", drain);
        return playerRuntime;
    }

    private static RpcGateway routedPlayerRpc(ClusterNodeConfig config, ClusterRpcGateway gateway) {
        return new RoutedRpcGateway(
                gateway,
                gateway.defaultCallOptions(),
                new PlayerGrayRoutePolicy(config.playerGrayRouteConfig())
        );
    }
}
