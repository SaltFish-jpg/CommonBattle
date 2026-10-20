package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.netty.NettyClusterTransportConfig;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyRpcReconnectEndToEndTest {
    private static final String OPERATION = "scene.reconnect.echo";

    @Test
    void retryReconnectsAfterRemoteServiceStartsAndCompletesRpc() throws Exception {
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", freePort());
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", freePort());
        Map<ServiceId, ServiceEndpoint> endpoints = Map.of(game.id(), game.endpoint(), scene.id(), scene.endpoint());
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        registry.register(game);
        registry.register(scene);
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
        PayloadCodecRegistry codecs = PayloadCodecRegistry.commonDefaults();
        NettyClusterTransportConfig transportConfig = new NettyClusterTransportConfig(
                Duration.ofSeconds(1), Duration.ofMillis(20), 16);
        AtomicReference<String> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        try (NettyClusterTransport gameTransport = new NettyClusterTransport(endpoints::get, codecs, transportConfig);
             ClusterRpcGateway gameRpc = new ClusterRpcGateway(game, directory(registry), topology, gameTransport);
             ResilientRpcGateway resilient = new ResilientRpcGateway(
                     gameRpc,
                     new RpcRetryPolicy(2, Duration.ofMillis(500)),
                     new RpcCircuitBreakerConfig(5, Duration.ofSeconds(2)))) {
            resilient.call(
                    RpcRequest.toService(scene.id(), OPERATION, "hello", String.class),
                    new RpcCallback<>() {
                        @Override
                        public void success(String value) {
                            response.set(value);
                            completed.countDown();
                        }

                        @Override
                        public void failure(Throwable error) {
                            failure.set(error);
                            completed.countDown();
                        }
                    }
            );
            assertEventually(() -> gameTransport.stats().connectionFailures() == 1);

            try (NettyClusterTransport sceneTransport = new NettyClusterTransport(endpoints::get, codecs, transportConfig);
                 ClusterRpcGateway sceneRpc = new ClusterRpcGateway(scene, directory(registry), topology, sceneTransport)) {
                sceneRpc.handle(OPERATION, (request, responder) -> responder.success("echo:" + request.payload()));

                assertTrue(completed.await(3, TimeUnit.SECONDS));
                assertEquals("echo:hello", response.get());
                assertNull(failure.get());
                assertEquals(2, gameTransport.stats().connectionAttempts());
                assertEquals(1, gameTransport.stats().connectionFailures());
                assertEquals(1, resilient.stats().retries());
                assertEquals(0, gameRpc.stats().pendingRequests());
            }
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
}
