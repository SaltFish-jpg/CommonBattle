package com.commonbattle.cluster.boot;

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
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.migration.AgentMigrationPayloadCodecs;
import com.commonbattle.actor.agent.remote.AgentDirectoryPayloadCodecs;

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Region 服启动入口。
 */
public final class RegionServerMain {
    private RegionServerMain() {
    }

    public static void main(String[] args) throws Exception {
        BootRuntime runtime = new BootRuntime().installShutdownHook();
        try {
            ClusterNodeConfig config = ClusterNodeConfig.load(args, "cluster/region.properties");
            config.validate(ServiceKind.REGION).throwIfInvalid();
            ServiceDescriptor local = ClusterDescriptors.fromConfig(config);
            ServiceDescriptor center = ClusterDescriptors.center(config);
            ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
            directory.seed(center);
            PayloadCodecRegistry codecs = ClusterEventPayloadCodecs.registerTo(
                    AgentMigrationPayloadCodecs.registerTo(
                            AgentDirectoryPayloadCodecs.registerTo(RegistryPayloadCodecs.registerTo(CrossPayloadCodecs.create()))
                    )
            );
            NettyClusterTransport transport = runtime.add("nettyTransport", new NettyClusterTransport(
                    new DirectoryEndpointView(directory, local, center),
                    codecs
            ));
            ClusterRpcGateway gateway = runtime.add("rpcGateway",
                    new ClusterRpcGateway(local, directory, ClusterTopology.defaultCrossServer(), transport));
            RemoteServiceRegistry registry = new RemoteServiceRegistry(local.id(), gateway, directory);
            ClusterNode node = runtime.add("clusterNode", new ClusterNode(registry, local, directory));
            ActorSystem actors = runtime.add("actors", new ActorSystem(config.actorSystemConfig()));
            node.start(
                    List.of(ServiceKind.GAME, ServiceKind.SCENE, ServiceKind.PROXY),
                    config.registryLeaseTtl(),
                    config.registryHeartbeatInterval()
            );
            runtime.add("opsHttp", BootOpsHttp.start(config, local, actors, directory, runtime.healthRegistry()));
            System.out.println("Region server started: " + local.id().wireName()
                    + ", ops=" + config.opsEndpoint().host() + ":" + config.opsEndpoint().port());
            new CountDownLatch(1).await();
        } catch (Exception e) {
            runtime.closeSuppressing(e);
            throw e;
        }
    }
}
