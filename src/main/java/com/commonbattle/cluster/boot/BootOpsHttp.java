package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterNode;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.event.ClusterEventCenter;
import com.commonbattle.cluster.event.ClusterEventSubscriptionManager;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.registry.RegistryLeaseReaper;
import com.commonbattle.cluster.registry.RegistryLeaseRenewer;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.game.config.GameConfigAutoRecovery;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.game.event.VersionedEventOutbox;
import com.commonbattle.game.profile.ProfileInterestView;
import com.commonbattle.observability.OpsHttpServer;
import com.commonbattle.observability.InMemoryOwnerRepairOpsAuditLog;
import com.commonbattle.observability.RuntimeHealthPolicy;
import com.commonbattle.observability.RuntimeHealthProbe;
import com.commonbattle.observability.RuntimeHealthRegistry;
import com.commonbattle.observability.ServerDrainController;
import com.commonbattle.observability.DrainResult;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Collection;
import java.util.List;

/**
 * 启动运维 HTTP 端点的辅助类。
 * 各独立进程复用同一套健康探针装配，避免 Game、Scene、Region、Proxy 的探活口径分叉。
 */
final class BootOpsHttp {
    private BootOpsHttp() {
    }

    static OpsHttpServer start(
            ClusterNodeConfig config,
            ServiceDescriptor local,
            ActorSystem actors,
            ClusterDirectory directory,
            BootRuntime runtime
    ) {
        RuntimeHealthRegistry registry = runtime.healthRegistry();
        Clock clock = Clock.systemUTC();
        InMemoryOwnerRepairOpsAuditLog ownerRepairOpsAudit =
                new InMemoryOwnerRepairOpsAuditLog(config.ownerRepairOpsAuditConfig());
        registry.register(ownerRepairOpsAudit);
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                clock,
                actors,
                lifecycles(registry, local, actors, clock),
                outbox(registry, clock),
                directory,
                registry,
                config.runtimeHealthPolicy()
        );
        return startServer(config, probe, clock, registry, ownerRepairOpsAudit, runtime);
    }

    static OpsHttpServer start(
            ClusterNodeConfig config,
            ServiceDescriptor local,
            ActorSystem actors,
            ClusterDirectory directory,
            ClusterRpcGateway gateway,
            NettyClusterTransport transport,
            ClusterNode node
    ) {
        return start(config, local, actors, directory, gateway,
                transport, node.leaseRenewer().stream().toList(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    static OpsHttpServer start(
            ClusterNodeConfig config,
            ServiceDescriptor local,
            ActorSystem actors,
            ClusterDirectory directory,
            ClusterRpcGateway gateway,
            NettyClusterTransport transport,
            ClusterNode node,
            Collection<LocalGameConfigCache> configCaches,
            Collection<GameConfigAutoRecovery> configRecoveries,
            Collection<ClusterEventSubscriptionManager> eventSubscriptions
    ) {
        return start(config, local, actors, directory, gateway, transport, node.leaseRenewer().stream().toList(),
                List.of(), configCaches, configRecoveries, List.of(), eventSubscriptions, List.of());
    }

    static OpsHttpServer start(
            ClusterNodeConfig config,
            ServiceDescriptor local,
            ActorSystem actors,
            ClusterDirectory directory,
            ClusterRpcGateway gateway,
            NettyClusterTransport transport,
            ClusterNode node,
            Collection<LocalGameConfigCache> configCaches,
            Collection<GameConfigAutoRecovery> configRecoveries,
            Collection<ClusterEventSubscriptionManager> eventSubscriptions,
            Collection<ProfileInterestView> profileInterests
    ) {
        return start(config, local, actors, directory, gateway, transport, node.leaseRenewer().stream().toList(),
                List.of(), configCaches, configRecoveries, List.of(), eventSubscriptions, profileInterests);
    }

    static OpsHttpServer start(
            ClusterNodeConfig config,
            ServiceDescriptor local,
            ActorSystem actors,
            ClusterDirectory directory,
            ClusterRpcGateway gateway,
            NettyClusterTransport transport,
            Collection<RegistryLeaseReaper> leaseReapers
    ) {
        return start(config, local, actors, directory, gateway, transport, List.of(), leaseReapers,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    static OpsHttpServer start(
            ClusterNodeConfig config,
            ServiceDescriptor local,
            ActorSystem actors,
            ClusterDirectory directory,
            ClusterRpcGateway gateway,
            NettyClusterTransport transport,
            Collection<RegistryLeaseReaper> leaseReapers,
            Collection<ClusterEventCenter> eventCenters
    ) {
        return start(config, local, actors, directory, gateway, transport, List.of(), leaseReapers,
                List.of(), List.of(), eventCenters, List.of(), List.of());
    }

    private static OpsHttpServer start(
            ClusterNodeConfig config,
            ServiceDescriptor local,
            ActorSystem actors,
            ClusterDirectory directory,
            ClusterRpcGateway gateway,
            NettyClusterTransport transport,
            Collection<RegistryLeaseRenewer> leaseRenewers,
            Collection<RegistryLeaseReaper> leaseReapers,
            Collection<LocalGameConfigCache> configCaches,
            Collection<GameConfigAutoRecovery> configRecoveries,
            Collection<ClusterEventCenter> eventCenters,
            Collection<ClusterEventSubscriptionManager> eventSubscriptions,
            Collection<ProfileInterestView> profileInterests
    ) {
        Clock clock = Clock.systemUTC();
        InMemoryOwnerRepairOpsAuditLog ownerRepairOpsAudit =
                new InMemoryOwnerRepairOpsAuditLog(config.ownerRepairOpsAuditConfig());
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(gateway);
        registry.register(transport);
        registry.register(leaseRenewers);
        registry.register(leaseReapers);
        registry.register(configCaches);
        registry.register(configRecoveries);
        registry.register(eventCenters);
        registry.register(eventSubscriptions);
        registry.register(profileInterests);
        registry.register(ownerRepairOpsAudit);
        RuntimeHealthProbe probe = new RuntimeHealthProbe(
                clock,
                actors,
                new AgentLifecycleManager(local.id(), actors, new InMemoryAgentDirectory(), clock),
                new InMemoryVersionedEventOutbox(clock),
                directory,
                registry,
                config.runtimeHealthPolicy()
        );
        return startServer(config, probe, clock, registry, ownerRepairOpsAudit, null);
    }

    private static OpsHttpServer startServer(
            ClusterNodeConfig config,
            RuntimeHealthProbe probe,
            Clock clock,
            RuntimeHealthRegistry registry,
            InMemoryOwnerRepairOpsAuditLog ownerRepairOpsAudit,
            BootRuntime runtime
    ) {
        ServiceEndpoint endpoint = config.opsEndpoint();
        ServerDrainController drainController = new ServerDrainController(
                probe,
                clock,
                duration -> Thread.sleep(duration.toMillis()),
                registry.drainableComponents()
        );
        if (runtime != null) {
            runtime.configureShutdownDrain(() -> {
                DrainResult result = drainController.awaitDrained(config.drainConfig());
                if (!result.drained()) {
                    String reason = result.reason().isBlank() ? "timeout" : result.reason();
                    throw new IllegalStateException("Server drain failed: " + reason);
                }
            });
        }
        OpsHttpServer server = new OpsHttpServer(
                new InetSocketAddress(endpoint.host(), endpoint.port()),
                probe,
                drainController,
                config.drainConfig(),
                registry.ownerEventRepairIsolationAdmins(),
                ownerRepairOpsAudit,
                ownerRepairOpsAudit,
                config.opsHttpSecurityConfig(),
                registry.actorIncidents(),
                registry.actorSlowTasks(),
                config.actorHotspotPolicy(),
                registry.actorHotspotOverrideAdmins(),
                registry.actorHotspotMigrationAdmins(),
                registry.ownerEventRepairRequestAdmins()
        );
        for (var audit : registry.eventGatedCommandAudits()) {
            server.attachEventGatedCommandAuditView(audit);
        }
        for (var audit : registry.commandAudits()) {
            server.attachPlayerCommandAuditView(audit);
        }
        for (var audit : registry.bagChangeAudits()) {
            server.attachBagChangeAuditView(audit);
        }
        for (var admin : registry.eventOutboxDeadLetterAdmins()) {
            server.attachEventOutboxDeadLetterAdmin(admin);
        }
        for (var admin : registry.eventInboxDeadLetterAdmins()) {
            server.attachEventInboxDeadLetterAdmin(admin);
        }
        server.start();
        return server;
    }

    private static VersionedEventOutbox outbox(RuntimeHealthRegistry registry, Clock clock) {
        return registry.outboxes().stream()
                .findFirst()
                .orElseGet(() -> new InMemoryVersionedEventOutbox(clock));
    }

    private static AgentLifecycleManager lifecycles(
            RuntimeHealthRegistry registry,
            ServiceDescriptor local,
            ActorSystem actors,
            Clock clock
    ) {
        return registry.lifecycleManagers().stream()
                .findFirst()
                .orElseGet(() -> new AgentLifecycleManager(local.id(), actors, new InMemoryAgentDirectory(), clock));
    }
}
