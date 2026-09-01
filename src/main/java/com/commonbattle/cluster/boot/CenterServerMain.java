package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.event.ClusterEventCenter;
import com.commonbattle.cluster.event.ClusterEventPayloadCodecs;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.registry.CenterRegistryEndpoint;
import com.commonbattle.cluster.registry.RegistryPayloadCodecs;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;

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
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
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
        new ClusterEventCenter(center, transport, gateway);
        System.out.println("Center server started: " + center.id().wireName());
        new CountDownLatch(1).await();
    }
}
