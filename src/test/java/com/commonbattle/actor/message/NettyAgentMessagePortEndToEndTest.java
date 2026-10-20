package com.commonbattle.actor.message;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.network.ClusterTransportException;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyAgentMessagePortEndToEndTest {
    private static final String OPERATION = "scene.echo";

    @Test
    void nettyResponseOnlyEnqueuesAndBusinessCallbackRunsInRequesterMailbox() throws Exception {
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", freePort());
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", freePort());
        Map<ServiceId, ServiceEndpoint> endpoints = Map.of(
                game.id(), game.endpoint(),
                scene.id(), scene.endpoint()
        );
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        registry.register(game);
        registry.register(scene);
        ClusterDirectory gameDirectory = directory(registry);
        ClusterDirectory sceneDirectory = directory(registry);
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        PayloadCodecRegistry codecs = PayloadCodecRegistry.commonDefaults();
        RecordingExecutor actorExecutor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(actorExecutor, 64);
        ActorRef requester = actors.actor("player-10001");
        AtomicReference<String> endpointThread = new AtomicReference<>();
        AtomicReference<String> callbackThread = new AtomicReference<>();
        AtomicReference<String> callbackActor = new AtomicReference<>();
        AtomicReference<String> response = new AtomicReference<>();

        try (NettyClusterTransport gameTransport = new NettyClusterTransport(endpoints::get, codecs);
             NettyClusterTransport sceneTransport = new NettyClusterTransport(endpoints::get, codecs);
             ClusterRpcGateway gameRpc = new ClusterRpcGateway(game, gameDirectory, topology, gameTransport);
             ClusterRpcGateway sceneRpc = new ClusterRpcGateway(scene, sceneDirectory, topology, sceneTransport);
             DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, gameRpc)) {
            sceneRpc.handle(OPERATION, (envelope, responder) -> {
                endpointThread.set(Thread.currentThread().getName());
                responder.success("echo:" + envelope.payload());
            });

            messages.callRemote(
                    requester,
                    RpcRequest.toService(scene.id(), OPERATION, "hello", String.class),
                    RemoteActorCallOptions.of(Duration.ofSeconds(3)),
                    new RemoteActorCallback<>() {
                        @Override
                        public void success(ActorContext context, String value) {
                            callbackThread.set(Thread.currentThread().getName());
                            callbackActor.set(ActorContext.requireCurrent(requester).self().id());
                            response.set(value);
                        }

                        @Override
                        public void failure(ActorContext context, AgentDeliveryResult delivery, Throwable error) {
                            throw new AssertionError(error);
                        }
                    }
            );

            assertEventually(() -> actors.stats().queuedTasks() == 1);
            assertNull(response.get());
            assertTrue(endpointThread.get().contains("EventLoop"));

            actorExecutor.runNext();

            assertEquals("echo:hello", response.get());
            assertEquals("player-10001", callbackActor.get());
            assertNotEquals(endpointThread.get(), callbackThread.get());
        } finally {
            actors.close();
        }
    }

    @Test
    void connectionFailureOnlyEnqueuesAndFailureRunsInRequesterMailbox() throws Exception {
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", freePort());
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", freePort());
        Map<ServiceId, ServiceEndpoint> endpoints = Map.of(game.id(), game.endpoint(), scene.id(), scene.endpoint());
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        registry.register(game);
        registry.register(scene);
        RecordingExecutor actorExecutor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(actorExecutor, 64);
        ActorRef requester = actors.actor("player-10001");
        AtomicReference<Throwable> callbackError = new AtomicReference<>();
        AtomicReference<AgentDeliveryStatus> callbackStatus = new AtomicReference<>();

        try (NettyClusterTransport gameTransport = new NettyClusterTransport(
                endpoints::get, PayloadCodecRegistry.commonDefaults());
             ClusterRpcGateway gameRpc = new ClusterRpcGateway(
                     game,
                     directory(registry),
                     new ClusterTopology().allow(ServiceKind.GAME, ServiceKind.SCENE),
                     gameTransport
             );
             DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, gameRpc)) {
            messages.callRemote(
                    requester,
                    RpcRequest.toService(scene.id(), OPERATION, "hello", String.class),
                    RemoteActorCallOptions.of(Duration.ofSeconds(30)),
                    new RemoteActorCallback<>() {
                        @Override
                        public void success(ActorContext context, String response) {
                            throw new AssertionError("unexpected success");
                        }

                        @Override
                        public void failure(ActorContext context, AgentDeliveryResult delivery, Throwable error) {
                            callbackStatus.set(delivery.status());
                            callbackError.set(error);
                        }
                    }
            );

            assertEventually(() -> actors.stats().queuedTasks() == 1);
            assertNull(callbackError.get());
            assertEquals(0, gameRpc.stats().pendingRequests());

            actorExecutor.runNext();

            assertEquals(AgentDeliveryStatus.REMOTE_UNAVAILABLE, callbackStatus.get());
            assertInstanceOf(ClusterTransportException.class, callbackError.get());
            assertEquals(0, messages.agentMessagePortStats().currentInFlightRemoteCalls());
        } finally {
            actors.close();
        }
    }

    private static ClusterDirectory directory(InMemoryServiceRegistry registry) {
        ClusterDirectory directory = new ClusterDirectory(registry);
        directory.watch(ServiceKind.GAME);
        directory.watch(ServiceKind.SCENE);
        return directory;
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(OPERATION),
                Map.of()
        );
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void assertEventually(BooleanSupplier assertion) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (assertion.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(assertion.getAsBoolean());
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            commands.add(command);
        }

        private void runNext() {
            Runnable command;
            synchronized (this) {
                command = commands.removeFirst();
            }
            command.run();
        }
    }
}
