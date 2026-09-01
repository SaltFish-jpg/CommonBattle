package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorSystem;
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

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Game 服启动入口。
 */
public final class GameServerMain {
    private GameServerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        ClusterNodeConfig config = ClusterNodeConfig.load(args, "cluster/game.properties");
        config.validate(ServiceKind.GAME).throwIfInvalid();
        ServiceDescriptor local = ClusterDescriptors.fromConfig(config);
        ServiceDescriptor center = ClusterDescriptors.center(config);
        ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
        directory.seed(center);
        PayloadCodecRegistry codecs = ClusterEventPayloadCodecs.registerTo(RegistryPayloadCodecs.registerTo(CrossPayloadCodecs.create()));
        NettyClusterTransport transport = new NettyClusterTransport(
                new DirectoryEndpointView(directory, local, center),
                codecs
        );
        ClusterRpcGateway gateway = new ClusterRpcGateway(local, directory, ClusterTopology.defaultCrossServer(), transport);
        RemoteServiceRegistry registry = new RemoteServiceRegistry(local.id(), gateway, directory);
        ClusterNode node = new ClusterNode(registry, local, directory);
        node.start(List.of(ServiceKind.SCENE, ServiceKind.PROXY, ServiceKind.REGION));
        ActorSystem actors = new ActorSystem(config.actorWorkers());
        System.out.println("Game server started: " + local.id().wireName() + ", actors=" + actors);
        new CountDownLatch(1).await();
    }
}
