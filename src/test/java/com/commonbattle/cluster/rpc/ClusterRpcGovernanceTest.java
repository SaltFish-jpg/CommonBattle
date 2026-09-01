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
import com.commonbattle.cluster.network.LocalClusterTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterRpcGovernanceTest {
    @Test
    void timeoutReleasesPendingCallback() throws InterruptedException {
        LocalClusterTransport transport = new LocalClusterTransport();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of());
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of("scene.slow"));
        ClusterRpcGateway sceneGateway = gateway(scene, transport, game, scene);
        sceneGateway.handle("scene.slow", (request, responder) -> {
        });
        ClusterRpcGateway gameGateway = gateway(game, transport, game, scene);
        RecordingCallback<String> callback = new RecordingCallback<>();

        gameGateway.call(
                new RpcRequest<>(ServiceKind.SCENE.name(), "scene.slow", "hello", String.class),
                callback,
                RpcCallOptions.of(Duration.ofMillis(20))
        );

        assertTrue(callback.awaitFailure());
        assertInstanceOf(RpcTimeoutException.class, callback.failure.get());
        assertEquals(0, gameGateway.stats().pendingRequests());
        assertEquals(1, gameGateway.stats().timedOutRequests());
    }

    @Test
    void pendingLimitRejectsNewCallBeforeTransportSend() {
        LocalClusterTransport transport = new LocalClusterTransport();
        RpcGovernanceConfig config = new RpcGovernanceConfig(
                Duration.ofSeconds(1),
                1,
                Duration.ofMillis(200),
                100
        );
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of());
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of("scene.hold"));
        ClusterRpcGateway sceneGateway = gateway(scene, transport, game, scene);
        sceneGateway.handle("scene.hold", (request, responder) -> {
        });
        ClusterRpcGateway gameGateway = gateway(game, transport, config, game, scene);
        RecordingCallback<String> first = new RecordingCallback<>();
        RecordingCallback<String> second = new RecordingCallback<>();

        gameGateway.call(new RpcRequest<>(ServiceKind.SCENE.name(), "scene.hold", "one", String.class), first);
        gameGateway.call(new RpcRequest<>(ServiceKind.SCENE.name(), "scene.hold", "two", String.class), second);

        assertInstanceOf(RpcRejectedException.class, second.failure.get());
        assertEquals(1, gameGateway.stats().pendingRequests());
        assertEquals(1, gameGateway.stats().rejectedRequests());
    }

    @Test
    void sendFailureCleansPendingCallback() {
        LocalClusterTransport transport = new LocalClusterTransport();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of());
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of("scene.enter"));
        ClusterRpcGateway gameGateway = gateway(game, transport, false, game, scene);
        RecordingCallback<String> callback = new RecordingCallback<>();

        gameGateway.call(new RpcRequest<>(ServiceKind.SCENE.name(), "scene.enter", "hello", String.class), callback);

        assertTrue(callback.failure.get() instanceof IllegalStateException);
        assertEquals(0, gameGateway.stats().pendingRequests());
        assertEquals(1, gameGateway.stats().failedRequests());
    }

    @Test
    void idempotencyKeyReusesCachedServerResponse() {
        LocalClusterTransport transport = new LocalClusterTransport();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of());
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of("scene.reserve"));
        AtomicInteger handlerRuns = new AtomicInteger();
        ClusterRpcGateway sceneGateway = gateway(scene, transport, game, scene);
        sceneGateway.handle("scene.reserve", (request, responder) ->
                responder.success("seat-" + handlerRuns.incrementAndGet()));
        ClusterRpcGateway gameGateway = gateway(game, transport, game, scene);
        RecordingCallback<String> first = new RecordingCallback<>();
        RecordingCallback<String> second = new RecordingCallback<>();
        RpcCallOptions options = RpcCallOptions.of(Duration.ofSeconds(1)).withIdempotencyKey("player-10001-enter-room-9");

        gameGateway.call(new RpcRequest<>(ServiceKind.SCENE.name(), "scene.reserve", "room-9", String.class), first, options);
        gameGateway.call(new RpcRequest<>(ServiceKind.SCENE.name(), "scene.reserve", "room-9", String.class), second, options);

        assertEquals("seat-1", first.success.get());
        assertEquals("seat-1", second.success.get());
        assertEquals(1, handlerRuns.get());
        assertEquals(1, sceneGateway.stats().idempotencyCacheSize());
    }

    @Test
    void slowCallIsRecordedWhenResponseExceedsThreshold() {
        LocalClusterTransport transport = new LocalClusterTransport();
        RpcGovernanceConfig config = new RpcGovernanceConfig(
                Duration.ofSeconds(1),
                100,
                Duration.ofMillis(1),
                100
        );
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of());
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of("scene.slow-success"));
        ClusterRpcGateway sceneGateway = gateway(scene, transport, game, scene);
        sceneGateway.handle("scene.slow-success", (request, responder) -> {
            try {
                TimeUnit.MILLISECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            responder.success("ok");
        });
        ClusterRpcGateway gameGateway = gateway(game, transport, config, game, scene);
        RecordingCallback<String> callback = new RecordingCallback<>();

        gameGateway.call(new RpcRequest<>(ServiceKind.SCENE.name(), "scene.slow-success", "hello", String.class), callback);

        assertEquals("ok", callback.success.get());
        assertEquals(1, gameGateway.stats().slowRequests());
        assertEquals(1, gameGateway.stats().succeededRequests());
    }

    private static ClusterRpcGateway gateway(
            ServiceDescriptor local,
            LocalClusterTransport transport,
            ServiceDescriptor... services
    ) {
        return gateway(local, transport, true, services);
    }

    private static ClusterRpcGateway gateway(
            ServiceDescriptor local,
            LocalClusterTransport transport,
            boolean bindTransport,
            ServiceDescriptor... services
    ) {
        return gateway(local, transport, RpcGovernanceConfig.defaults(), bindTransport, services);
    }

    private static ClusterRpcGateway gateway(
            ServiceDescriptor local,
            LocalClusterTransport transport,
            RpcGovernanceConfig config,
            ServiceDescriptor... services
    ) {
        return gateway(local, transport, config, true, services);
    }

    private static ClusterRpcGateway gateway(
            ServiceDescriptor local,
            LocalClusterTransport transport,
            RpcGovernanceConfig config,
            boolean bindTransport,
            ServiceDescriptor... services
    ) {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        for (ServiceDescriptor service : services) {
            registry.register(service);
        }
        ClusterDirectory directory = new ClusterDirectory(registry);
        for (ServiceKind kind : ServiceKind.values()) {
            directory.watch(kind);
        }
        ClusterTopology direct = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME)
                .allow(ServiceKind.GAME, ServiceKind.GAME)
                .allow(ServiceKind.SCENE, ServiceKind.SCENE);
        return new ClusterRpcGateway(local, directory, direct, transport, bindTransport, config);
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> topics) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                topics,
                Map.of()
        );
    }

    private static final class RecordingCallback<T> implements RpcCallback<T> {
        private final AtomicReference<T> success = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public void success(T response) {
            success.set(response);
        }

        @Override
        public void failure(Throwable error) {
            failure.set(error);
        }

        private boolean awaitFailure() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (System.nanoTime() < deadline) {
                if (failure.get() != null) {
                    return true;
                }
                TimeUnit.MILLISECONDS.sleep(5);
            }
            return failure.get() != null;
        }
    }
}
