package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.remote.CenterAgentDirectoryEndpoint;
import com.commonbattle.cluster.event.ClusterEventCenter;
import com.commonbattle.cluster.event.ClusterEventSubscriptionLeaseReaper;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.registry.CenterRegistryEndpoint;
import com.commonbattle.cluster.registry.RegistryLeaseReaper;
import com.commonbattle.cluster.registry.RegistrySubscriptionLeaseReaper;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.config.GameConfigCenterEndpoint;
import com.commonbattle.game.config.GameConfigCenterPublisher;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.InMemoryGameConfigRegistry;
import com.commonbattle.game.shop.InMemoryShopStockRepository;
import com.commonbattle.game.shop.SerializedShopStockRepository;
import com.commonbattle.game.shop.ShopStockReservationRepository;
import com.commonbattle.game.shop.ShopStockReservationRetentionScheduler;
import com.commonbattle.game.shop.ShopStockReservationRetentionService;
import com.commonbattle.game.shop.ShopStockEndpoint;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;

/**
 * Center 服启动入口。
 */
public final class CenterServerMain {
    private CenterServerMain() {
    }

    public static void main(String[] args) throws Exception {
        BootRuntime runtime = new BootRuntime().installShutdownHook();
        try {
            ClusterNodeConfig config = ClusterNodeConfig.load(args, "cluster/center.properties");
            config.validate(ServiceKind.CENTER).throwIfInvalid();
            ServiceDescriptor center = ClusterDescriptors.fromConfig(config);
            PayloadCodecRegistry codecs = BootPayloadCodecs.centerServer();
            Clock clock = Clock.systemUTC();
            InMemoryServiceRegistry registry = new InMemoryServiceRegistry(clock, config.registryHistoryLimit());
            runtime.observe("registryHistory", registry);
            ClusterDirectory directory = runtime.add("directory", new ClusterDirectory(registry));
            for (ServiceKind kind : ServiceKind.values()) {
                directory.watch(kind);
            }
            NettyClusterTransport transport = runtime.add("nettyTransport", new NettyClusterTransport(
                    new DirectoryEndpointView(directory, center, center),
                    codecs
            ));
            registry.register(center);
            ClusterRpcGateway gateway = runtime.add("rpcGateway",
                    new ClusterRpcGateway(center, directory, ClusterTopology.defaultCrossServer(), transport));
            CenterRegistryEndpoint registryEndpoint = runtime.add(
                    "centerRegistryEndpoint",
                    new CenterRegistryEndpoint(
                            center,
                            registry,
                            transport,
                            gateway,
                            clock,
                            config.registrySubscriptionLeaseTtl()
                    )
            );
            new CenterAgentDirectoryEndpoint(new InMemoryAgentDirectory()).bind(gateway);
            ShopStockReservationRepository stockRepository = shopStockRepository(config, clock);
            new ShopStockEndpoint(stockRepository).bind(gateway);
            if (config.shopStockReservationRetentionEnabled()) {
                ShopStockReservationRetentionService stockRetention = new ShopStockReservationRetentionService(stockRepository);
                runtime.observe("shopStockReservationRetention", stockRetention);
                ShopStockReservationRetentionScheduler stockRetentionScheduler = runtime.add(
                        "shopStockReservationRetentionScheduler",
                        new ShopStockReservationRetentionScheduler(stockRetention, config.shopStockReservationScanInterval())
                );
                stockRetentionScheduler.start();
            }
            RegistryLeaseReaper leaseReaper = runtime.add(
                    "leaseReaper",
                    new RegistryLeaseReaper(registry, clock, config.registryLeaseScanInterval())
            );
            leaseReaper.start();
            RegistrySubscriptionLeaseReaper subscriptionLeaseReaper = runtime.add(
                    "subscriptionLeaseReaper",
                    new RegistrySubscriptionLeaseReaper(
                            registryEndpoint,
                            clock,
                            config.registrySubscriptionLeaseScanInterval()
                    )
            );
            subscriptionLeaseReaper.start();
            ActorSystem actors = BootActors.configure(runtime, config, clock);
            ClusterEventCenter eventCenter = new ClusterEventCenter(
                    center,
                    transport,
                    gateway,
                    config.eventHistoryPolicy(),
                    clock,
                    config.eventSubscriptionLeaseTtl()
            );
            runtime.observe("eventCenter", eventCenter);
            ClusterEventSubscriptionLeaseReaper eventSubscriptionLeaseReaper = runtime.add(
                    "eventSubscriptionLeaseReaper",
                    new ClusterEventSubscriptionLeaseReaper(
                            eventCenter,
                            clock,
                            config.eventSubscriptionLeaseScanInterval()
                    )
            );
            eventSubscriptionLeaseReaper.start();
            runtime.add("opsHttp", BootOpsHttp.start(config, center, actors, directory, runtime.healthRegistry()));
            GameConfigCenterPublisher configPublisher = new GameConfigCenterPublisher(
                    new InMemoryGameConfigRegistry(new GameConfigValidator(), clock),
                    eventCenter::publishLocal
            );
            configPublisher.publish(ExampleGameConfigs.basic(1, clock.instant()));
            new GameConfigCenterEndpoint(configPublisher).bind(gateway);
            System.out.println("Center server started: " + center.id().wireName()
                    + ", ops=" + config.opsEndpoint().host() + ":" + config.opsEndpoint().port());
            new CountDownLatch(1).await();
        } catch (Exception e) {
            runtime.closeSuppressing(e);
            throw e;
        }
    }

    private static ShopStockReservationRepository shopStockRepository(ClusterNodeConfig config, Clock clock) {
        return switch (config.shopStockStoreKind()) {
            case MEMORY -> new InMemoryShopStockRepository(clock, config.shopStockReservationTtl());
            case ATOMIC_MEMORY -> new SerializedShopStockRepository(
                    new InMemoryAtomicBytesStore(),
                    clock,
                    config.shopStockReservationTtl()
            );
        };
    }
}
