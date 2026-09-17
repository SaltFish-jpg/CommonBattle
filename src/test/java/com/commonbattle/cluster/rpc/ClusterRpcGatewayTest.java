package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.AgentDeliveryStatus;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.cluster.network.ForwardingProxy;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.example.cross.AgentStatus;
import com.commonbattle.example.cross.CrossServerPlayerAgent;
import com.commonbattle.example.cross.EnterSceneFailureCode;
import com.commonbattle.example.cross.EnterSceneRequest;
import com.commonbattle.example.cross.EnterSceneResult;
import com.commonbattle.example.cross.LeaveSceneRequest;
import com.commonbattle.example.cross.LeaveSceneResult;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.example.cross.scene.ProfileAwareSceneService;
import com.commonbattle.example.cross.scene.MultiSmallSceneService;
import com.commonbattle.example.cross.scene.SceneEnterTargetSelector;
import com.commonbattle.example.cross.scene.SceneHostingMode;
import com.commonbattle.example.cross.scene.ScenePlacement;
import com.commonbattle.example.cross.scene.SceneRuntimeMetadata;
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void playerAgentReceivesStructuredSceneMailboxPressureFailureThroughRpc() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        RecordingExecutor executor = new RecordingExecutor();

        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
        registry.register(game);
        registry.register(scene);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterDirectory sceneDirectory = new ClusterDirectory(registry);
        sceneDirectory.watch(ServiceKind.GAME);
        try (ActorSystem actors = new ActorSystem(executor, 1)) {
            ClusterRpcGateway sceneGateway = new ClusterRpcGateway(scene, sceneDirectory, topology, transport);
            sceneGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.failure(
                    new RpcStructuredException(
                            "mailbox_pressure:target",
                            "mailbox_pressure:target",
                            Duration.ofMillis(50)
                    )
            ));
            ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
            CrossServerPlayerAgent agent = new CrossServerPlayerAgent(actors, gameGateway, 10001L);

            agent.enterScene("room-9");
            executor.runNext();
            executor.runNext();

            assertEquals(AgentStatus.FAILED, agent.status());
            assertEquals(AgentDeliveryStatus.REJECTED, agent.lastDeliveryStatus());
            assertEquals(EnterSceneFailureCode.RPC_REJECTED, agent.lastEnterSceneFailure().code());
            assertEquals("mailbox_pressure:target", agent.lastEnterSceneFailure().message());
            assertEquals(50, agent.lastEnterSceneFailure().retryAfter().toMillis());
            assertEquals(0, gameGateway.stats().pendingRequests());
            assertEquals(1, gameGateway.stats().failedRequests());
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

    @Test
    void rpcTargetResolutionSkipsDrainingServices() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor drainingScene = ServiceMetadata.withDraining(
                descriptor(ServiceKind.SCENE, "scene-draining", 9002, Set.of(SceneOperations.ENTER)),
                true
        );
        ServiceDescriptor activeScene = descriptor(ServiceKind.SCENE, "scene-active", 9003, Set.of(SceneOperations.ENTER));
        registry.register(drainingScene);
        registry.register(activeScene);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterDirectory activeSceneDirectory = new ClusterDirectory(registry);
        activeSceneDirectory.watch(ServiceKind.GAME);
        ClusterRpcGateway activeGateway = new ClusterRpcGateway(activeScene, activeSceneDirectory, topology, transport);
        activeGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("active"));
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        RecordingCallback callback = new RecordingCallback();

        gameGateway.call(new com.commonbattle.actor.rpc.RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                "payload",
                String.class
        ), callback);

        assertEquals("active", callback.response.get());
    }

    @Test
    void rpcGatewayRejectsInboundRequestAfterLocalDrainStarted() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
        registry.register(scene);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterDirectory sceneDirectory = new ClusterDirectory(registry);
        sceneDirectory.watch(ServiceKind.GAME);
        ClusterRpcGateway sceneGateway = new ClusterRpcGateway(scene, sceneDirectory, topology, transport);
        sceneGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("accepted"));
        sceneGateway.beginDrain();
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        FailureCallback callback = new FailureCallback();

        gameGateway.call(request(), callback);

        assertTrue(sceneGateway.isDraining());
        assertTrue(callback.error.get().getMessage().contains("is draining"));
        assertEquals(1, sceneGateway.stats().rejectedRequests());
        assertEquals(0, gameGateway.stats().pendingRequests());
    }

    @Test
    void rpcGatewayTurnsHandlerExceptionIntoFailureResponse() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
        registry.register(scene);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterDirectory sceneDirectory = new ClusterDirectory(registry);
        sceneDirectory.watch(ServiceKind.GAME);
        ClusterRpcGateway sceneGateway = new ClusterRpcGateway(scene, sceneDirectory, topology, transport);
        sceneGateway.handle(SceneOperations.ENTER, (request, responder) -> {
            throw new IllegalStateException("scene is full");
        });
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        FailureCallback callback = new FailureCallback();

        gameGateway.call(request(), callback);

        assertTrue(callback.error.get().getMessage().contains("scene is full"));
        assertEquals(0, gameGateway.stats().pendingRequests());
        assertEquals(1, gameGateway.stats().failedRequests());
    }

    @Test
    void sceneEnterSelectorRoutesLargeSceneBySceneId() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor worldOne = sceneDescriptor("scene-world-1", 9002, Map.of(
                "scene.mode", SceneHostingMode.LARGE_SCENE_SHARD.name(),
                "scene.id", "world-1",
                SceneRuntimeMetadata.MAX_SHARD_PLAYERS, "7",
                ServiceMetadata.LOAD_USED, "80",
                ServiceMetadata.LOAD_CAPACITY, "100"
        ));
        ServiceDescriptor worldTwo = sceneDescriptor("scene-world-2", 9003, Map.of(
                "scene.mode", SceneHostingMode.LARGE_SCENE_SHARD.name(),
                "scene.id", "world-2",
                SceneRuntimeMetadata.MAX_SHARD_PLAYERS, "1",
                ServiceMetadata.LOAD_USED, "10",
                ServiceMetadata.LOAD_CAPACITY, "100"
        ));
        registry.register(worldOne);
        registry.register(worldTwo);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterDirectory worldOneDirectory = new ClusterDirectory(registry);
        worldOneDirectory.watch(ServiceKind.GAME);
        ClusterDirectory worldTwoDirectory = new ClusterDirectory(registry);
        worldTwoDirectory.watch(ServiceKind.GAME);
        ClusterRpcGateway worldOneGateway = new ClusterRpcGateway(worldOne, worldOneDirectory, topology, transport);
        worldOneGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("world-1"));
        ClusterRpcGateway worldTwoGateway = new ClusterRpcGateway(worldTwo, worldTwoDirectory, topology, transport);
        worldTwoGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("world-2"));
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport)
                .addTargetSelector(new SceneEnterTargetSelector());
        RecordingCallback callback = new RecordingCallback();

        gameGateway.call(new RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                new EnterSceneRequest(10001L, "world-1"),
                String.class
        ), callback);

        assertEquals("world-1", callback.response.get());
    }

    @Test
    void sceneEnterSelectorSkipsFullSmallSceneService() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor fullSmall = sceneDescriptor("scene-full", 9002, Map.of(
                "scene.mode", SceneHostingMode.MULTI_SMALL_SCENE.name(),
                "scene.capacity", "2",
                SceneRuntimeMetadata.ACTIVE_SCENES, "2",
                SceneRuntimeMetadata.ACTIVE_PLAYERS, "20",
                ServiceMetadata.LOAD_USED, "2",
                ServiceMetadata.LOAD_CAPACITY, "2"
        ));
        ServiceDescriptor availableSmall = sceneDescriptor("scene-available", 9003, Map.of(
                "scene.mode", SceneHostingMode.MULTI_SMALL_SCENE.name(),
                "scene.capacity", "10",
                SceneRuntimeMetadata.ACTIVE_SCENES, "3",
                SceneRuntimeMetadata.ACTIVE_PLAYERS, "30",
                ServiceMetadata.LOAD_USED, "3",
                ServiceMetadata.LOAD_CAPACITY, "10"
        ));
        registry.register(fullSmall);
        registry.register(availableSmall);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterDirectory fullDirectory = new ClusterDirectory(registry);
        fullDirectory.watch(ServiceKind.GAME);
        ClusterDirectory availableDirectory = new ClusterDirectory(registry);
        availableDirectory.watch(ServiceKind.GAME);
        ClusterRpcGateway fullGateway = new ClusterRpcGateway(fullSmall, fullDirectory, topology, transport);
        fullGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("full"));
        ClusterRpcGateway availableGateway = new ClusterRpcGateway(availableSmall, availableDirectory, topology, transport);
        availableGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("available"));
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport)
                .addTargetSelector(new SceneEnterTargetSelector());
        RecordingCallback callback = new RecordingCallback();

        gameGateway.call(new RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                new EnterSceneRequest(10001L, "room-9"),
                String.class
        ), callback);

        assertEquals("available", callback.response.get());
    }

    @Test
    void sceneEnterSelectorSkipsCapacityDegradedSmallSceneService() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor degradedSmall = sceneDescriptor("scene-hot", 9002, Map.of(
                "scene.mode", SceneHostingMode.MULTI_SMALL_SCENE.name(),
                "scene.capacity", "10",
                SceneRuntimeMetadata.ACTIVE_SCENES, "5",
                SceneRuntimeMetadata.ACTIVE_PLAYERS, "200",
                SceneRuntimeMetadata.CAPACITY_STATUS, SceneRuntimeMetadata.CAPACITY_DEGRADED,
                SceneRuntimeMetadata.CAPACITY_REASON, SceneRuntimeMetadata.ACTIVE_PLAYERS,
                ServiceMetadata.LOAD_USED, "5",
                ServiceMetadata.LOAD_CAPACITY, "10"
        ));
        ServiceDescriptor availableSmall = sceneDescriptor("scene-available", 9003, Map.of(
                "scene.mode", SceneHostingMode.MULTI_SMALL_SCENE.name(),
                "scene.capacity", "10",
                SceneRuntimeMetadata.ACTIVE_SCENES, "6",
                SceneRuntimeMetadata.ACTIVE_PLAYERS, "300",
                SceneRuntimeMetadata.CAPACITY_STATUS, SceneRuntimeMetadata.CAPACITY_OK,
                ServiceMetadata.LOAD_USED, "6",
                ServiceMetadata.LOAD_CAPACITY, "10"
        ));
        registry.register(degradedSmall);
        registry.register(availableSmall);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterDirectory degradedDirectory = new ClusterDirectory(registry);
        degradedDirectory.watch(ServiceKind.GAME);
        ClusterDirectory availableDirectory = new ClusterDirectory(registry);
        availableDirectory.watch(ServiceKind.GAME);
        ClusterRpcGateway degradedGateway = new ClusterRpcGateway(degradedSmall, degradedDirectory, topology, transport);
        degradedGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("degraded"));
        ClusterRpcGateway availableGateway = new ClusterRpcGateway(availableSmall, availableDirectory, topology, transport);
        availableGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("available"));
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport)
                .addTargetSelector(new SceneEnterTargetSelector());
        RecordingCallback callback = new RecordingCallback();

        gameGateway.call(new RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                new EnterSceneRequest(10001L, "room-9"),
                String.class
        ), callback);

        assertEquals("available", callback.response.get());
    }

    @Test
    void sceneEnterSelectorRejectsDegradedLargeSceneWithoutSmallFallback() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor degradedWorld = sceneDescriptor("scene-world-1", 9002, Map.of(
                "scene.mode", SceneHostingMode.LARGE_SCENE_SHARD.name(),
                "scene.id", "world-1",
                SceneRuntimeMetadata.MAX_SHARD_PLAYERS, "600",
                SceneRuntimeMetadata.CAPACITY_STATUS, SceneRuntimeMetadata.CAPACITY_DEGRADED,
                SceneRuntimeMetadata.CAPACITY_REASON, SceneRuntimeMetadata.MAX_SHARD_PLAYERS,
                ServiceMetadata.LOAD_USED, "900",
                ServiceMetadata.LOAD_CAPACITY, "1000"
        ));
        ServiceDescriptor smallScene = sceneDescriptor("scene-small", 9003, Map.of(
                "scene.mode", SceneHostingMode.MULTI_SMALL_SCENE.name(),
                "scene.capacity", "10",
                SceneRuntimeMetadata.ACTIVE_SCENES, "1",
                SceneRuntimeMetadata.CAPACITY_STATUS, SceneRuntimeMetadata.CAPACITY_OK
        ));
        registry.register(degradedWorld);
        registry.register(smallScene);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport)
                .addTargetSelector(new SceneEnterTargetSelector());
        FailureCallback callback = new FailureCallback();

        gameGateway.call(new RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                new EnterSceneRequest(10001L, "world-1"),
                String.class
        ), callback);

        assertInstanceOf(RpcNoRoutableServiceException.class, callback.error.get());
        assertEquals(0, gameGateway.stats().pendingRequests());
        assertEquals(1, gameGateway.stats().failedRequests());
    }

    @Test
    void sceneEnterSelectorRejectsWhenAllSmallSceneServicesAreFull() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor fullSmall = sceneDescriptor("scene-full", 9002, Map.of(
                "scene.mode", SceneHostingMode.MULTI_SMALL_SCENE.name(),
                "scene.capacity", "2",
                SceneRuntimeMetadata.ACTIVE_SCENES, "2",
                SceneRuntimeMetadata.ACTIVE_PLAYERS, "20",
                ServiceMetadata.LOAD_USED, "2",
                ServiceMetadata.LOAD_CAPACITY, "2"
        ));
        registry.register(fullSmall);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport)
                .addTargetSelector(new SceneEnterTargetSelector());
        FailureCallback callback = new FailureCallback();

        gameGateway.call(new RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                new EnterSceneRequest(10001L, "room-9"),
                String.class
        ), callback);

        assertInstanceOf(RpcNoRoutableServiceException.class, callback.error.get());
        assertEquals(0, gameGateway.stats().pendingRequests());
        assertEquals(1, gameGateway.stats().failedRequests());
    }

    @Test
    void rpcTargetResolutionRoundRobinsAcrossRoutableServices() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor sceneOne = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
        ServiceDescriptor sceneTwo = descriptor(ServiceKind.SCENE, "scene-2", 9003, Set.of(SceneOperations.ENTER));
        registry.register(sceneOne);
        registry.register(sceneTwo);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterDirectory sceneOneDirectory = new ClusterDirectory(registry);
        sceneOneDirectory.watch(ServiceKind.GAME);
        ClusterDirectory sceneTwoDirectory = new ClusterDirectory(registry);
        sceneTwoDirectory.watch(ServiceKind.GAME);
        ClusterRpcGateway sceneOneGateway = new ClusterRpcGateway(sceneOne, sceneOneDirectory, topology, transport);
        sceneOneGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("scene-1"));
        ClusterRpcGateway sceneTwoGateway = new ClusterRpcGateway(sceneTwo, sceneTwoDirectory, topology, transport);
        sceneTwoGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("scene-2"));
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        RecordingCallback first = new RecordingCallback();
        RecordingCallback second = new RecordingCallback();

        gameGateway.call(request(), first);
        gameGateway.call(request(), second);

        assertEquals(Set.of("scene-1", "scene-2"), Set.of(first.response.get(), second.response.get()));
    }

    @Test
    void rpcTargetResolutionPrefersLowerLoadService() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor highLoad = ServiceMetadata.withLoad(
                descriptor(ServiceKind.SCENE, "scene-high", 9002, Set.of(SceneOperations.ENTER)),
                90,
                100
        );
        ServiceDescriptor lowLoad = ServiceMetadata.withLoad(
                descriptor(ServiceKind.SCENE, "scene-low", 9003, Set.of(SceneOperations.ENTER)),
                20,
                100
        );
        registry.register(highLoad);
        registry.register(lowLoad);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterDirectory highLoadDirectory = new ClusterDirectory(registry);
        highLoadDirectory.watch(ServiceKind.GAME);
        ClusterDirectory lowLoadDirectory = new ClusterDirectory(registry);
        lowLoadDirectory.watch(ServiceKind.GAME);
        ClusterRpcGateway highGateway = new ClusterRpcGateway(highLoad, highLoadDirectory, topology, transport);
        highGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("high"));
        ClusterRpcGateway lowGateway = new ClusterRpcGateway(lowLoad, lowLoadDirectory, topology, transport);
        lowGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("low"));
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        RecordingCallback callback = new RecordingCallback();

        gameGateway.call(request(), callback);

        assertEquals("low", callback.response.get());
    }

    @Test
    void rpcTargetResolutionUsesOperationCapabilityForGrayRelease() {
        String newOperation = "scene.inspect.v2";
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor oldScene = ServiceMetadata.withProtocolVersion(
                descriptor(ServiceKind.SCENE, "scene-old", 9002, Set.of(SceneOperations.ENTER)),
                1
        );
        ServiceDescriptor newScene = ServiceMetadata.withProtocolVersion(
                descriptor(ServiceKind.SCENE, "scene-new", 9003, Set.of(SceneOperations.ENTER, newOperation)),
                2
        );
        registry.register(oldScene);
        registry.register(newScene);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterDirectory newSceneDirectory = new ClusterDirectory(registry);
        newSceneDirectory.watch(ServiceKind.GAME);
        ClusterRpcGateway newGateway = new ClusterRpcGateway(newScene, newSceneDirectory, topology, transport);
        newGateway.handle(newOperation, (request, responder) -> responder.success("v" + newScene.protocolVersion()));
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        RecordingCallback callback = new RecordingCallback();

        gameGateway.call(new com.commonbattle.actor.rpc.RpcRequest<>(
                ServiceKind.SCENE.name(),
                newOperation,
                "payload",
                String.class
        ), callback);

        assertEquals("v2", callback.response.get());
    }

    @Test
    void rpcTargetResolutionCanRequireRouteTagForGrayRelease() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor stableScene = ServiceMetadata.withRouteTag(
                descriptor(ServiceKind.SCENE, "scene-stable", 9002, Set.of(SceneOperations.ENTER)),
                "stable"
        );
        ServiceDescriptor grayScene = ServiceMetadata.withRouteTag(
                descriptor(ServiceKind.SCENE, "scene-gray", 9003, Set.of(SceneOperations.ENTER)),
                "gray"
        );
        registry.register(stableScene);
        registry.register(grayScene);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterDirectory stableDirectory = new ClusterDirectory(registry);
        stableDirectory.watch(ServiceKind.GAME);
        ClusterDirectory grayDirectory = new ClusterDirectory(registry);
        grayDirectory.watch(ServiceKind.GAME);
        ClusterRpcGateway stableGateway = new ClusterRpcGateway(stableScene, stableDirectory, topology, transport);
        stableGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("stable"));
        ClusterRpcGateway grayGateway = new ClusterRpcGateway(grayScene, grayDirectory, topology, transport);
        grayGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("gray"));
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        RecordingCallback callback = new RecordingCallback();

        gameGateway.call(request(), callback, RpcCallOptions.of(Duration.ofSeconds(1))
                .withRequiredTargetMetadata(ServiceMetadata.ROUTE_TAG, "gray"));

        assertEquals("gray", callback.response.get());
    }

    @Test
    void routedGatewayAppliesPlayerGrayRoutePolicyToSceneRpc() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor stableScene = ServiceMetadata.withRouteTag(
                descriptor(ServiceKind.SCENE, "scene-stable", 9002, Set.of(SceneOperations.ENTER)),
                "stable"
        );
        ServiceDescriptor grayScene = ServiceMetadata.withRouteTag(
                descriptor(ServiceKind.SCENE, "scene-gray", 9003, Set.of(SceneOperations.ENTER)),
                "gray"
        );
        registry.register(stableScene);
        registry.register(grayScene);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterDirectory stableDirectory = new ClusterDirectory(registry);
        stableDirectory.watch(ServiceKind.GAME);
        ClusterDirectory grayDirectory = new ClusterDirectory(registry);
        grayDirectory.watch(ServiceKind.GAME);
        ClusterRpcGateway stableGateway = new ClusterRpcGateway(stableScene, stableDirectory, topology, transport);
        stableGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("stable"));
        ClusterRpcGateway grayGateway = new ClusterRpcGateway(grayScene, grayDirectory, topology, transport);
        grayGateway.handle(SceneOperations.ENTER, (request, responder) -> responder.success("gray"));
        ClusterRpcGateway clusterGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        RoutedRpcGateway gameGateway = new RoutedRpcGateway(
                clusterGateway,
                clusterGateway.defaultCallOptions(),
                new PlayerGrayRoutePolicy(new PlayerGrayRouteConfig(
                        true,
                        "stable",
                        "gray",
                        0,
                        Set.of(10001L),
                        Set.of(SceneOperations.ENTER)
                ))
        );
        RecordingCallback grayCallback = new RecordingCallback();
        RecordingCallback stableCallback = new RecordingCallback();

        gameGateway.call(new RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                new EnterSceneRequest(10001L, "room-9"),
                String.class
        ), grayCallback);
        gameGateway.call(new RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                new EnterSceneRequest(20002L, "room-9"),
                String.class
        ), stableCallback);

        assertEquals("gray", grayCallback.response.get());
        assertEquals("stable", stableCallback.response.get());
    }

    @Test
    void rpcTargetResolutionDoesNotFallbackWhenRequiredMetadataIsMissing() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor stableScene = ServiceMetadata.withRouteTag(
                descriptor(ServiceKind.SCENE, "scene-stable", 9002, Set.of(SceneOperations.ENTER)),
                "stable"
        );
        registry.register(stableScene);
        ClusterDirectory gameDirectory = new ClusterDirectory(registry);
        gameDirectory.watch(ServiceKind.SCENE);
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        FailureCallback callback = new FailureCallback();

        gameGateway.call(request(), callback, RpcCallOptions.of(Duration.ofSeconds(1))
                .withRequiredTargetMetadata(ServiceMetadata.ROUTE_TAG, "gray"));

        assertInstanceOf(RpcNoRoutableServiceException.class, callback.error.get());
        assertEquals(0, gameGateway.stats().pendingRequests());
        assertEquals(1, gameGateway.stats().failedRequests());
    }

    @Test
    void exactServiceTargetStillHonorsRequiredMetadata() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor gameTwo = ServiceMetadata.withRouteTag(
                descriptor(ServiceKind.GAME, "game-2", 9002, Set.of("agent.migration.accept")),
                "stable"
        );
        registry.register(gameOne);
        registry.register(gameTwo);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.watch(ServiceKind.GAME);
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        FailureCallback callback = new FailureCallback();

        gameOneGateway.call(RpcRequest.toService(
                gameTwo.id(),
                "agent.migration.accept",
                "payload",
                String.class
        ), callback, RpcCallOptions.of(Duration.ofSeconds(1))
                .withRequiredTargetMetadata(ServiceMetadata.ROUTE_TAG, "gray"));

        assertInstanceOf(RpcNoRoutableServiceException.class, callback.error.get());
        assertEquals(0, gameOneGateway.stats().pendingRequests());
        assertEquals(1, gameOneGateway.stats().failedRequests());
    }

    @Test
    void rpcRequestCanTargetExactServiceId() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor gameTwo = descriptor(ServiceKind.GAME, "game-2", 9002, Set.of("agent.migration.accept"));
        registry.register(gameOne);
        registry.register(gameTwo);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.watch(ServiceKind.GAME);
        ClusterDirectory gameTwoDirectory = new ClusterDirectory(registry);
        gameTwoDirectory.watch(ServiceKind.GAME);
        ClusterRpcGateway gameTwoGateway = new ClusterRpcGateway(gameTwo, gameTwoDirectory, topology, transport);
        gameTwoGateway.handle("agent.migration.accept", (request, responder) -> responder.success("game-2"));
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        RecordingCallback callback = new RecordingCallback();

        gameOneGateway.call(RpcRequest.toService(
                gameTwo.id(),
                "agent.migration.accept",
                "payload",
                String.class
        ), callback);

        assertEquals("game-2", callback.response.get());
    }

    private static com.commonbattle.actor.rpc.RpcRequest<String> request() {
        return new com.commonbattle.actor.rpc.RpcRequest<>(
                ServiceKind.SCENE.name(),
                SceneOperations.ENTER,
                "payload",
                String.class
        );
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> topics) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                topics,
                Map.of()
        );
    }

    private static ServiceDescriptor sceneDescriptor(String node, int port, Map<String, String> metadata) {
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.SCENE, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(SceneOperations.ENTER),
                metadata
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

    private static final class RecordingCallback implements com.commonbattle.actor.rpc.RpcCallback<String> {
        private final AtomicReference<String> response = new AtomicReference<>();

        @Override
        public void success(String response) {
            this.response.set(response);
        }

        @Override
        public void failure(Throwable error) {
            throw new AssertionError(error);
        }
    }

    private static final class FailureCallback implements com.commonbattle.actor.rpc.RpcCallback<String> {
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void success(String response) {
            throw new AssertionError("unexpected success: " + response);
        }

        @Override
        public void failure(Throwable error) {
            this.error.set(error);
        }
    }
}
