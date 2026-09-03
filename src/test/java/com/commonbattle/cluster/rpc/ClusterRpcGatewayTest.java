package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.ForwardingProxy;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.example.cross.AgentStatus;
import com.commonbattle.example.cross.CrossServerPlayerAgent;
import com.commonbattle.example.cross.EnterSceneRequest;
import com.commonbattle.example.cross.EnterSceneResult;
import com.commonbattle.example.cross.LeaveSceneRequest;
import com.commonbattle.example.cross.LeaveSceneResult;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.example.cross.scene.ProfileAwareSceneService;
import com.commonbattle.example.cross.scene.MultiSmallSceneService;
import com.commonbattle.example.cross.scene.ScenePlacement;
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ClusterRpcGatewayTest {
    @Test
    void playerAgentEntersSceneThroughRegistryAndProxyForwarding() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        RecordingExecutor executor = new RecordingExecutor();

        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor proxy = descriptor(ServiceKind.PROXY, "proxy-1", 9002, Set.of("proxy.forward"));
        try (ActorSystem actors = new ActorSystem(executor, 1)) {
            MultiSmallSceneService sceneService = MultiSmallSceneService.create(
                    actors,
                    "r1",
                    "scene-small-1",
                    new ServiceEndpoint("127.0.0.1", 9003),
                    200
            );

            registry.register(game);
            registry.register(proxy);
            registry.register(sceneService.descriptor());

            ClusterDirectory gameDirectory = new ClusterDirectory(registry);
            gameDirectory.watch(ServiceKind.SCENE);
            gameDirectory.watch(ServiceKind.PROXY);
            ClusterDirectory sceneDirectory = new ClusterDirectory(registry);
            sceneDirectory.watch(ServiceKind.PROXY);

            new ForwardingProxy(transport).bind(proxy);
            ClusterRpcGateway sceneGateway = new ClusterRpcGateway(sceneService.descriptor(), sceneDirectory, topology, transport);
            sceneGateway.handle(SceneOperations.ENTER, (request, responder) -> {
                EnterSceneRequest payload = assertInstanceOf(EnterSceneRequest.class, request.payload());
                sceneService.place(payload.sceneId(), 0, 0);
                responder.success(new EnterSceneResult(payload.playerId(), payload.sceneId(), 7001L));
            });

            ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
            CrossServerPlayerAgent agent = new CrossServerPlayerAgent(actors, gameGateway, 10001L);

            agent.enterScene("room-9");
            executor.runNext();
            assertEquals(AgentStatus.ENTERING_SCENE, agent.status());

            executor.runNext();
            assertEquals(AgentStatus.IN_SCENE, agent.status());
            assertEquals("room-9", agent.sceneId());
        }
    }

    @Test
    void playerAgentEnterAndLeaveThroughProxyDriveSceneProfileInterests() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        RecordingExecutor gameExecutor = new RecordingExecutor();
        RecordingExecutor sceneExecutor = new RecordingExecutor();
        RecordingInterestControl interests = new RecordingInterestControl();

        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor proxy = descriptor(ServiceKind.PROXY, "proxy-1", 9002, Set.of("proxy.forward"));
        try (ActorSystem gameActors = new ActorSystem(gameExecutor, 1);
             ActorSystem sceneActors = new ActorSystem(sceneExecutor, 1)) {
            MultiSmallSceneService delegate = MultiSmallSceneService.create(
                    sceneActors,
                    "r1",
                    "scene-small-1",
                    new ServiceEndpoint("127.0.0.1", 9003),
                    200
            );
            SceneProfileAwarenessAgent profiles = new SceneProfileAwarenessAgent(
                    new DefaultAgentMessagePort(sceneActors, new NoopGateway()),
                    sceneActors.actor("scene-profile"),
                    interests
            );
            ProfileAwareSceneService sceneService = new ProfileAwareSceneService(delegate, profiles);

            registry.register(game);
            registry.register(proxy);
            registry.register(sceneService.descriptor());

            ClusterDirectory gameDirectory = new ClusterDirectory(registry);
            gameDirectory.watch(ServiceKind.SCENE);
            gameDirectory.watch(ServiceKind.PROXY);
            ClusterDirectory sceneDirectory = new ClusterDirectory(registry);
            sceneDirectory.watch(ServiceKind.PROXY);

            new ForwardingProxy(transport).bind(proxy);
            ClusterRpcGateway sceneGateway = new ClusterRpcGateway(sceneService.descriptor(), sceneDirectory, topology, transport);
            sceneGateway.handle(SceneOperations.ENTER, (request, responder) -> {
                EnterSceneRequest payload = assertInstanceOf(EnterSceneRequest.class, request.payload());
                ScenePlacement placement = sceneService.enter(payload.playerId(), payload.sceneId(), 0, 0);
                responder.success(new EnterSceneResult(payload.playerId(), placement.sceneId(), 7001L));
            });
            sceneGateway.handle(SceneOperations.LEAVE, (request, responder) -> {
                LeaveSceneRequest payload = assertInstanceOf(LeaveSceneRequest.class, request.payload());
                boolean left = sceneService.leave(payload.playerId(), payload.sceneId());
                responder.success(new LeaveSceneResult(payload.playerId(), payload.sceneId(), left));
            });

            ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
            CrossServerPlayerAgent agent = new CrossServerPlayerAgent(gameActors, gameGateway, 10001L);

            agent.enterScene("room-9");
            gameExecutor.runNext();
            sceneExecutor.runNext();
            gameExecutor.runNext();

            assertEquals(AgentStatus.IN_SCENE, agent.status());
            assertEquals(List.of(10001L), interests.watched);

            agent.leaveScene();
            gameExecutor.runNext();
            sceneExecutor.runNext();
            gameExecutor.runNext();

            assertEquals(AgentStatus.LOCAL, agent.status());
            assertEquals(List.of(10001L), interests.unwatched);
        }
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> topics) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                topics,
                Map.of()
        );
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.removeFirst().run();
        }
    }

    private static final class RecordingInterestControl implements ProfileInterestControl {
        private final List<Long> watched = new ArrayList<>();
        private final List<Long> unwatched = new ArrayList<>();

        @Override
        public void watch(long playerId) {
            watched.add(playerId);
        }

        @Override
        public void unwatch(long playerId) {
            unwatched.add(playerId);
        }
    }

    private static final class NoopGateway implements com.commonbattle.actor.rpc.RpcGateway {
        @Override
        public <T> void call(com.commonbattle.actor.rpc.RpcRequest<T> request, com.commonbattle.actor.rpc.RpcCallback<T> callback) {
        }
    }
}
