package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.cluster.event.ClusterEventCenter;
import com.commonbattle.cluster.event.ClusterEventPayloadCodecs;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.registry.CenterRegistryEndpoint;
import com.commonbattle.cluster.registry.RegistryLeaseReaper;
import com.commonbattle.cluster.registry.RegistryPayloadCodecs;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.config.GameConfigCenterEndpoint;
import com.commonbattle.game.config.GameConfigCenterPublisher;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.InMemoryGameConfigRegistry;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;

/**
 * Center 服启动入口。
 */
public final class CenterServerMain {
    private CenterServerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        ClusterNodeConfig config = ClusterNodeConfig.load(args, "cluster/center.properties");
        config.validate(ServiceKind.CENTER).throwIfInvalid();
        ServiceDescriptor center = ClusterDescriptors.fromConfig(config);
        PayloadCodecRegistry codecs = ClusterEventPayloadCodecs.registerTo(
                RegistryPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults())
        );
        Clock clock = Clock.systemUTC();
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry(clock);
        ClusterDirectory directory = new ClusterDirectory(registry);
        for (ServiceKind kind : ServiceKind.values()) {
            directory.watch(kind);
        }
        NettyClusterTransport transport = new NettyClusterTransport(
                new DirectoryEndpointView(directory, center, center),
                codecs
        );
        registry.register(center);
        ClusterRpcGateway gateway = new ClusterRpcGateway(center, directory, ClusterTopology.defaultCrossServer(), transport);
        new CenterRegistryEndpoint(center, registry, transport, gateway);
        RegistryLeaseReaper leaseReaper = new RegistryLeaseReaper(registry, clock, config.registryLeaseScanInterval());
        leaseReaper.start();
        ActorSystem actors = new ActorSystem(config.actorWorkers());
        ClusterEventCenter eventCenter = new ClusterEventCenter(center, transport, gateway, config.eventHistoryPolicy());
        BootOpsHttp.start(config, center, actors, directory, gateway, transport,
                java.util.List.of(leaseReaper), java.util.List.of(eventCenter));
        GameConfigCenterPublisher configPublisher = new GameConfigCenterPublisher(
                new InMemoryGameConfigRegistry(new GameConfigValidator(), clock),
                eventCenter::publishLocal
        );
        configPublisher.publish(ExampleGameConfigs.basic(1, clock.instant()));
        new GameConfigCenterEndpoint(configPublisher).bind(gateway);
        System.out.println("Center server started: " + center.id().wireName()
                + ", ops=" + config.opsEndpoint().host() + ":" + config.opsEndpoint().port());
        new CountDownLatch(1).await();
    }
}
