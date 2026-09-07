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
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.player.PlayerBusinessCommandBinder;
import com.commonbattle.game.player.PlayerBusinessCommandEndpoint;
import com.commonbattle.game.player.PlayerBusinessCommandGateway;
import com.commonbattle.game.player.PlayerBusinessCommandHandler;
import com.commonbattle.game.player.PlayerBusinessResponseHub;
import com.commonbattle.game.player.PlayerAgentDrainService;
import com.commonbattle.game.player.PlayerAutoSaveScheduler;
import com.commonbattle.game.player.PlayerGameAgentManager;
import com.commonbattle.game.player.PlayerStateRepository;
import com.commonbattle.game.profile.PlayerProfileEventProjector;
import com.commonbattle.game.profile.PlayerProfileSnapshotProjector;
import com.commonbattle.game.profile.ProfileSnapshotRepository;
import com.commonbattle.game.session.InMemoryPlayerCommandAuditLog;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerLoginService;
import com.commonbattle.game.session.PlayerSessionRegistry;

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
        PlayerSessionRegistry sessions,
        PlayerLoginService logins,
        PlayerCommandDispatcher dispatcher,
        PlayerBusinessResponseHub businessResponses,
        PlayerBusinessCommandGateway businessCommands,
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
                local,
                actors,
                gateway,
                new RemoteAgentDirectory(gateway),
                BootPlayerStateRepository.configure(runtime, config),
                configCache,
                profileSnapshots,
                playerDomainEvents,
                profileEvents,
                clock,
                config.gameServerOpenTime(),
                config.playerCommandRateLimitPolicy(),
                config.playerAutoSaveEnabled(),
                config.playerAutoSaveInitialDelay(),
                config.playerAutoSaveInterval()
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
                playerDomainEvents,
                profileEvents,
                clock,
                serverOpenTime,
                commandRateLimitPolicy,
                false,
                Duration.ZERO,
                Duration.ofSeconds(60)
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
            AgentRateLimitPolicy commandRateLimitPolicy,
            boolean autoSaveEnabled,
            Duration autoSaveInitialDelay,
            Duration autoSaveInterval
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(actors, "actors");
        Objects.requireNonNull(rpc, "rpc");
        Objects.requireNonNull(agentDirectory, "agentDirectory");
        Objects.requireNonNull(stateRepository, "stateRepository");
        Objects.requireNonNull(configCache, "configCache");
        Objects.requireNonNull(profileSnapshots, "profileSnapshots");
        Objects.requireNonNull(playerDomainEvents, "playerDomainEvents");
        Objects.requireNonNull(profileEvents, "profileEvents");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(serverOpenTime, "serverOpenTime");
        Objects.requireNonNull(commandRateLimitPolicy, "commandRateLimitPolicy");
        Objects.requireNonNull(autoSaveInitialDelay, "autoSaveInitialDelay");
        Objects.requireNonNull(autoSaveInterval, "autoSaveInterval");

        DefaultAgentMessagePort messages = runtime.add("agentMessages", new DefaultAgentMessagePort(actors, rpc));
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(local.id(), actors, agentDirectory, clock);
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
                new PlayerProfileSnapshotProjector(profileSnapshots)::project
        );
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(clock);
        PlayerLoginService logins = new PlayerLoginService(agents, sessions);
        InMemoryPlayerCommandAuditLog audit = new InMemoryPlayerCommandAuditLog();
        PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                sessions,
                new PlayerCommandSequencer(),
                new AdmissionControlledAgentRouter(
                        new TokenBucketAgentAdmissionController(commandRateLimitPolicy, clock),
                        new LifecycleAwareAgentRouter(lifecycles, messages)
                ),
                audit,
                playerId -> configCache.resolve(playerId).version(),
                clock
        );
        PlayerBusinessResponseHub businessResponses = new PlayerBusinessResponseHub();
        PlayerBusinessCommandGateway businessCommands = new PlayerBusinessCommandGateway(
                dispatcher,
                rpc,
                businessResponses
        );
        PlayerBusinessCommandBinder.registerExamples(
                dispatcher,
                new PlayerBusinessCommandHandler(agents::getOrCreate, businessResponses)
        );
        if (rpc instanceof ClusterRpcGateway clusterRpc) {
            new PlayerBusinessCommandEndpoint(businessCommands).bind(clusterRpc);
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
                sessions,
                logins,
                dispatcher,
                businessResponses,
                businessCommands,
                audit,
                autoSaves,
                drain
        );
        runtime.observe("agentLifecycles", lifecycles);
        runtime.observe("playerStateRepository", stateRepository);
        runtime.observe("playerAgents", agents);
        runtime.observe("playerSessions", sessions);
        runtime.observe("playerLogins", logins);
        runtime.observe("playerCommandAudit", audit);
        runtime.observe("playerCommandDispatcher", dispatcher);
        runtime.observe("playerBusinessResponses", businessResponses);
        runtime.observe("playerAgentDrain", drain);
        return playerRuntime;
    }
}
