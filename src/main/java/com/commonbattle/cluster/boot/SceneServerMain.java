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
import com.commonbattle.example.cross.scene.LargeSceneShardService;
import com.commonbattle.example.cross.scene.MultiSmallSceneService;
import com.commonbattle.example.cross.scene.SceneHostingMode;
import com.commonbattle.example.cross.scene.SceneServiceStrategy;

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Scene 服启动入口。
 */
public final class SceneServerMain {
    private SceneServerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        ClusterNodeConfig config = ClusterNodeConfig.load(args, "cluster/scene-small.properties");
        config.validate(ServiceKind.SCENE).throwIfInvalid();
        ActorSystem actors = new ActorSystem(config.actorWorkers());
        SceneServiceStrategy sceneService = sceneService(config, actors);
        ServiceDescriptor local = sceneService.descriptor();
        ServiceDescriptor center = ClusterDescriptors.center(config);
        ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
        directory.seed(center);
        PayloadCodecRegistry codecs = ClusterEventPayloadCodecs.registerTo(RegistryPayloadCodecs.registerTo(CrossPayloadCodecs.create()));
        NettyClusterTransport transport = new NettyClusterTransport(
                new DirectoryEndpointView(directory, local, center),
                codecs
        );
        ClusterRpcGateway gateway = new ClusterRpcGateway(local, directory, ClusterTopology.defaultCrossServer(), transport);
        gateway.handle("scene.enter", (request, responder) -> {
            com.commonbattle.example.cross.EnterSceneRequest payload =
                    (com.commonbattle.example.cross.EnterSceneRequest) request.payload();
            sceneService.place(payload.sceneId(), 0, 0);
            responder.success(new com.commonbattle.example.cross.EnterSceneResult(payload.playerId(), payload.sceneId(), 0));
        });
        RemoteServiceRegistry registry = new RemoteServiceRegistry(local.id(), gateway, directory);
        ClusterNode node = new ClusterNode(registry, local, directory);
        node.start(List.of(ServiceKind.GAME, ServiceKind.PROXY, ServiceKind.REGION));
        System.out.println("Scene server started: " + local.id().wireName());
        new CountDownLatch(1).await();
    }

    private static SceneServiceStrategy sceneService(ClusterNodeConfig config, ActorSystem actors) {
        if (config.sceneMode() == SceneHostingMode.LARGE_SCENE_SHARD) {
            return LargeSceneShardService.create(
                    actors,
                    config.region(),
                    config.node(),
                    config.endpoint(),
                    config.sceneId(),
                    config.sceneShards()
            );
        }
        return MultiSmallSceneService.create(
                actors,
                config.region(),
                config.node(),
                config.endpoint(),
                config.sceneCapacity()
        );
    }
}
