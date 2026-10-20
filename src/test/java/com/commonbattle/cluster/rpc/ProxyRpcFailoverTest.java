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
import com.commonbattle.cluster.network.ForwardingProxy;
import com.commonbattle.cluster.network.LocalClusterTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyRpcFailoverTest {
    private static final String OPERATION = "scene.proxy.echo";

    @Test
    void downstreamForwardFailureCompletesPendingRpcImmediately() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001);
        ServiceDescriptor proxy = descriptor(ServiceKind.PROXY, "proxy-1", 9002);
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9003);
        registry.register(game);
        registry.register(proxy);
        registry.register(scene);
        LocalClusterTransport transport = new LocalClusterTransport();
        ForwardingProxy forwarder = new ForwardingProxy(transport);
        forwarder.bind(proxy);
        RecordingCallback callback = new RecordingCallback();

        try (ClusterRpcGateway gateway = new ClusterRpcGateway(
                game, directory(registry), ClusterTopology.defaultCrossServer(), transport
        )) {
            gateway.call(RpcRequest.toService(scene.id(), OPERATION, "hello", String.class), callback);

            assertInstanceOf(ClusterForwardingException.class, callback.failure.get());
            assertEquals(0, gateway.stats().pendingRequests());
            assertEquals(1, gateway.stats().failedRequests());
            assertEquals(1, gateway.stats().forwardingFailedRequests());
            assertEquals(1, forwarder.stats().sentFailureReceipts());
        }
    }

    @Test
    void resilientRpcRotatesToSecondProxyAfterFirstWriteFails() throws InterruptedException {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9011);
        ServiceDescriptor proxyA = descriptor(ServiceKind.PROXY, "proxy-a", 9012);
        ServiceDescriptor proxyB = descriptor(ServiceKind.PROXY, "proxy-b", 9013);
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9014);
        registry.register(game);
        registry.register(proxyA);
        registry.register(proxyB);
        registry.register(scene);
        LocalClusterTransport transport = new LocalClusterTransport();
        new ForwardingProxy(transport).bind(proxyB);

        try (ClusterRpcGateway sceneGateway = new ClusterRpcGateway(
                scene, directory(registry), ClusterTopology.defaultCrossServer(), transport
        ); ClusterRpcGateway gameGateway = new ClusterRpcGateway(
                game, directory(registry), ClusterTopology.defaultCrossServer(), transport
        ); ResilientRpcGateway resilient = new ResilientRpcGateway(
                gameGateway,
                new RpcRetryPolicy(2, Duration.ZERO),
                new RpcCircuitBreakerConfig(5, Duration.ofSeconds(1))
        )) {
            sceneGateway.handle(OPERATION, (request, responder) -> responder.success("echo:" + request.payload()));
            RecordingCallback callback = new RecordingCallback();

            resilient.call(RpcRequest.toService(scene.id(), OPERATION, "hello", String.class), callback);

            assertTrue(callback.completed.await(1, TimeUnit.SECONDS));
            assertEquals("echo:hello", callback.success.get());
            assertNull(callback.failure.get());
            assertEquals(0, resilient.stats().retries());
            assertEquals(1, gameGateway.stats().requestFailovers());
            assertEquals(0, gameGateway.stats().pendingRequests());
        }
    }

    private static ClusterDirectory directory(InMemoryServiceRegistry registry) {
        ClusterDirectory directory = new ClusterDirectory(registry);
        directory.watch(ServiceKind.GAME);
        directory.watch(ServiceKind.SCENE);
        directory.watch(ServiceKind.PROXY);
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

    private static final class RecordingCallback implements RpcCallback<String> {
        private final AtomicReference<String> success = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void success(String response) {
            success.set(response);
            completed.countDown();
        }

        @Override
        public void failure(Throwable error) {
            failure.set(error);
            completed.countDown();
        }
    }
}
