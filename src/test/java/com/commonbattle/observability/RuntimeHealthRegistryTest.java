package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.migration.AgentMigrationCoordinator;
import com.commonbattle.actor.agent.migration.AgentMigrationExecutor;
import com.commonbattle.actor.agent.migration.AgentMigrationPolicy;
import com.commonbattle.actor.agent.migration.AgentMigrationRecoveryService;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStore;
import com.commonbattle.actor.agent.migration.RemoteAgentMigrationClient;
import com.commonbattle.actor.rpc.ActorRpcClient;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterNode;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.registry.RegistryLeaseRenewer;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.registry.RemoteRegistryRecoveryStats;
import com.commonbattle.cluster.registry.RemoteRegistryRecoveryView;
import com.commonbattle.cluster.registry.RegistrySubscriptionStats;
import com.commonbattle.cluster.registry.RegistrySubscriptionView;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RoutedRpcGateway;
import com.commonbattle.cluster.rpc.RpcCallOptions;
import com.commonbattle.game.config.GameConfigAutoRecovery;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemCatalog;
import com.commonbattle.game.player.AsyncShopPurchaseStats;
import com.commonbattle.game.player.AsyncShopPurchaseView;
import com.commonbattle.game.player.PlayerBusinessResponseHub;
import com.commonbattle.game.player.NettyPlayerGatewayStats;
import com.commonbattle.game.player.PlayerGatewayView;
import com.commonbattle.game.profile.LocalProfileCache;
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.profile.ProfileRuntime;
import com.commonbattle.game.scene.SceneRuntimeStats;
import com.commonbattle.game.scene.SceneRuntimeView;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerDeliveryOverflowStrategy;
import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.shop.ShopCatalog;
import com.commonbattle.game.shop.ShopService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeHealthRegistryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void registerCollectsKnownRuntimeComponentsWithoutDuplicates() {
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        GameConfigAutoRecovery configRecovery = new GameConfigAutoRecovery(callback -> {
        });
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        ActorRpcClient actorRpc = new ActorRpcClient(actors, actors.actor("player-1"), new NoopRpcGateway());
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(descriptor().id(), actors,
                new InMemoryAgentDirectory(), CLOCK);
        AgentMigrationExecutor migrationExecutor = new AgentMigrationExecutor("migration-test", 1, 16);
        InMemoryAgentDirectory agents = new InMemoryAgentDirectory();
        ClusterRpcGateway gateway = new ClusterRpcGateway(
                descriptor(),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                new ClusterTopology(),
                new LocalClusterTransport(),
                false
        );
        AgentMigrationCoordinator migrations = new AgentMigrationCoordinator(
                new AgentLifecycleManager(descriptor().id(), actors, agents, CLOCK),
                agents,
                new RemoteAgentMigrationClient(gateway),
                Runnable::run
        );
        AgentMigrationRecoveryService migrationRecovery = new AgentMigrationRecoveryService(
                AgentMigrationTaskStore.none(),
                agents,
                new AgentLifecycleManager(descriptor().id(), actors, agents, CLOCK),
                new RemoteAgentMigrationClient(gateway),
                Runnable::run,
                AgentMigrationPolicy.defaults(),
                CLOCK
        );
        ProfileRuntime profileRuntime = new ProfileRuntime(
                new LocalProfileCache(),
                ProfileInterestControl.noop(),
                playerId -> java.util.Optional.empty()
        );
        SceneRuntimeView sceneRuntime = () -> new SceneRuntimeStats(1, 3, 8, 2);
        ShopService shopService = new ShopService(
                new ShopCatalog(),
                new BagService(new ItemCatalog()),
                CLOCK,
                ZoneOffset.UTC
        );
        PlayerBusinessResponseHub businessResponses = new PlayerBusinessResponseHub();
        PlayerOutboundDeliveryHub outboundDeliveries = new PlayerOutboundDeliveryHub(
                new InMemoryPlayerSessionRegistry(CLOCK),
                CLOCK,
                16,
                PlayerDeliveryOverflowStrategy.DROP_OLDEST
        );
        PlayerGatewayView playerGateway = () -> new NettyPlayerGatewayStats(1, 0, 0, 0, 0, 2, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0);
        AsyncShopPurchaseView asyncShopPurchases = AsyncShopPurchaseStats::empty;
        InMemoryServiceRegistry serviceRegistry = new InMemoryServiceRegistry(CLOCK, 16);
        RemoteRegistryRecoveryView remoteRegistryRecovery = () -> RemoteRegistryRecoveryStats.empty();
        RegistrySubscriptionView registrySubscriptions = () -> RegistrySubscriptionStats.empty();
        RoutedRpcGateway routedRpc = new RoutedRpcGateway(
                gateway,
                RpcCallOptions.of(Duration.ofSeconds(1)),
                (request, baseOptions) -> baseOptions
        );

        registry.register(List.of(cache, configRecovery, actorRpc, lifecycles, migrations, migrationExecutor,
                migrationRecovery, profileRuntime, sceneRuntime, shopService, businessResponses, asyncShopPurchases,
                outboundDeliveries, playerGateway, routedRpc, serviceRegistry, remoteRegistryRecovery, registrySubscriptions));
        registry.register(cache);

        try {
            assertEquals(1, registry.configCaches().size());
            assertEquals(1, registry.configRecoveries().size());
            assertEquals(1, registry.actorRpcClients().size());
            assertEquals(1, registry.lifecycleManagers().size());
            assertEquals(1, registry.migrationCoordinators().size());
            assertEquals(1, registry.migrationExecutors().size());
            assertEquals(1, registry.migrationRecoveries().size());
            assertEquals(1, registry.profileRuntimes().size());
            assertEquals(1, registry.sceneRuntimes().size());
            assertEquals(1, registry.shopRuntimes().size());
            assertEquals(1, registry.playerBusinessResponses().size());
            assertEquals(1, registry.playerOutboundDeliveries().size());
            assertEquals(1, registry.playerGateways().size());
            assertEquals(1, registry.asyncShopPurchases().size());
            assertEquals(1, registry.rpcRoutePolicies().size());
            assertEquals(1, registry.registryHistories().size());
            assertEquals(1, registry.registrySubscriptions().size());
            assertEquals(1, registry.remoteRegistryRecoveries().size());
        } finally {
            migrationExecutor.close();
        }
    }

    @Test
    void leaseRenewersIncludeStartedClusterNodes() {
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        InMemoryServiceRegistry serviceRegistry = new InMemoryServiceRegistry(CLOCK);
        ClusterNode node = new ClusterNode(serviceRegistry, descriptor(), new ClusterDirectory(serviceRegistry));

        registry.register(node);
        assertEquals(0, registry.leaseRenewers().size());

        try {
            node.start(Set.of(), Duration.ofSeconds(5), Duration.ofSeconds(1));

            assertEquals(1, registry.leaseRenewers().size());
            assertEquals(1, registry.drainableComponents().size());
        } finally {
            node.close();
        }
    }

    private static ServiceDescriptor descriptor() {
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                new ServiceEndpoint("127.0.0.1", 9001),
                Set.of(),
                Map.of()
        );
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
