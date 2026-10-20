package com.commonbattle.cluster.netty;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.network.ClusterSendCallback;
import com.commonbattle.cluster.network.ClusterTransportException;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.registry.RegistryPayloadCodecs;
import com.commonbattle.example.cross.CrossPayloadCodecs;
import com.commonbattle.example.cross.EnterSceneRequest;
import com.commonbattle.example.cross.SceneOperations;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyClusterTransportTest {
    @Test
    void sendsEnvelopeOverNettyWithProtobufCodec() throws Exception {
        int gamePort = freePort();
        int scenePort = freePort();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", gamePort, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", scenePort, Set.of(SceneOperations.ENTER));
        Map<ServiceId, ServiceEndpoint> endpoints = Map.of(
                game.id(), game.endpoint(),
                scene.id(), scene.endpoint()
        );
        PayloadCodecRegistry codecs = RegistryPayloadCodecs.registerTo(CrossPayloadCodecs.create());
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<ClusterEnvelope> envelopeRef = new AtomicReference<>();

        try (NettyClusterTransport gameTransport = new NettyClusterTransport(endpoints::get, codecs);
             NettyClusterTransport sceneTransport = new NettyClusterTransport(endpoints::get, codecs)) {
            gameTransport.bind(game, ignored -> {
            });
            sceneTransport.bind(scene, envelope -> {
                envelopeRef.set(envelope);
                received.countDown();
            });

            gameTransport.send(scene.id(), new ClusterEnvelope(
                    1,
                    game.id(),
                    scene.id(),
                    SceneOperations.ENTER,
                    new EnterSceneRequest(10001L, "room-1", 3, 4)
            ));

            assertTrue(received.await(3, TimeUnit.SECONDS));
            ClusterEnvelope envelope = envelopeRef.get();
            assertEquals(SceneOperations.ENTER, envelope.operation());
            EnterSceneRequest payload = assertInstanceOf(EnterSceneRequest.class, envelope.payload());
            assertEquals("room-1", payload.sceneId());
            assertEquals(3, payload.chunkX());
            assertEquals(4, payload.chunkY());
            assertEventually(() -> gameTransport.stats().sentEnvelopes() == 1);
            assertEquals(1, gameTransport.stats().activeConnections());
            assertEquals(1, gameTransport.stats().connectionAttempts());
            assertEquals(0, gameTransport.stats().connectionFailures());
            assertEquals(0, gameTransport.stats().inFlightWrites());
            assertEquals(1, sceneTransport.stats().receivedEnvelopes());
        }
    }

    @Test
    void recordsConnectionFailureWhenTargetIsUnavailable() throws Exception {
        int gamePort = freePort();
        int missingScenePort = freePort();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", gamePort, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", missingScenePort, Set.of(SceneOperations.ENTER));
        Map<ServiceId, ServiceEndpoint> endpoints = Map.of(
                game.id(), game.endpoint(),
                scene.id(), scene.endpoint()
        );
        PayloadCodecRegistry codecs = RegistryPayloadCodecs.registerTo(CrossPayloadCodecs.create());

        NettyClusterTransportConfig config = new NettyClusterTransportConfig(
                Duration.ofSeconds(1), Duration.ofSeconds(5), 16);
        try (NettyClusterTransport gameTransport = new NettyClusterTransport(endpoints::get, codecs, config)) {
            gameTransport.bind(game, ignored -> {
            });
            CountDownLatch failed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            gameTransport.send(scene.id(), new ClusterEnvelope(
                    1,
                    game.id(),
                    scene.id(),
                    SceneOperations.ENTER,
                    new EnterSceneRequest(10001L, "room-1")
            ), failureCallback(failure, failed));

            assertTrue(failed.await(3, TimeUnit.SECONDS));
            assertInstanceOf(ClusterTransportException.class, failure.get());
            assertEquals(1, gameTransport.stats().connectionAttempts());
            assertEquals(1, gameTransport.stats().connectionFailures());
            assertEquals(0, gameTransport.stats().activeConnections());
            assertEquals(0, gameTransport.stats().sentEnvelopes());
            assertEquals(0, gameTransport.stats().inFlightWrites());

            CountDownLatch backoffFailure = new CountDownLatch(1);
            AtomicReference<Throwable> backoffError = new AtomicReference<>();
            gameTransport.send(scene.id(), envelope(game, scene, 2),
                    failureCallback(backoffError, backoffFailure));
            assertTrue(backoffFailure.await(1, TimeUnit.SECONDS));
            assertTrue(backoffError.get().getMessage().contains("Reconnect backoff"));
            assertEquals(1, gameTransport.stats().connectionAttempts());
        }
    }

    @Test
    void sendAfterCloseIsRejectedAndRecordedAsFailedWrite() throws Exception {
        int gamePort = freePort();
        int scenePort = freePort();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", gamePort, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", scenePort, Set.of(SceneOperations.ENTER));
        Map<ServiceId, ServiceEndpoint> endpoints = Map.of(
                game.id(), game.endpoint(),
                scene.id(), scene.endpoint()
        );
        PayloadCodecRegistry codecs = RegistryPayloadCodecs.registerTo(CrossPayloadCodecs.create());
        NettyClusterTransport transport = new NettyClusterTransport(endpoints::get, codecs);

        transport.close();
        transport.close();

        assertThrows(IllegalStateException.class, () -> transport.send(scene.id(), new ClusterEnvelope(
                1,
                game.id(),
                scene.id(),
                SceneOperations.ENTER,
                new EnterSceneRequest(10001L, "room-1")
        )));
        assertThrows(IllegalStateException.class, () -> transport.bind(game, ignored -> {
        }));
        assertEquals(1, transport.stats().failedWrites());
        assertEquals(0, transport.stats().inFlightWrites());
        assertEquals(0, transport.stats().connectionAttempts());
    }

    @Test
    void reconnectsWhenTargetStartsAfterInitialConnectionFailure() throws Exception {
        int gamePort = freePort();
        int scenePort = freePort();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", gamePort, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", scenePort, Set.of(SceneOperations.ENTER));
        Map<ServiceId, ServiceEndpoint> endpoints = Map.of(game.id(), game.endpoint(), scene.id(), scene.endpoint());
        PayloadCodecRegistry codecs = RegistryPayloadCodecs.registerTo(CrossPayloadCodecs.create());
        NettyClusterTransportConfig transportConfig = new NettyClusterTransportConfig(
                Duration.ofSeconds(1), Duration.ZERO, 16);

        try (NettyClusterTransport gameTransport = new NettyClusterTransport(endpoints::get, codecs, transportConfig)) {
            gameTransport.bind(game, ignored -> {
            });
            CountDownLatch initialFailure = new CountDownLatch(1);
            gameTransport.send(scene.id(), envelope(game, scene, 1), failureCallback(new AtomicReference<>(), initialFailure));
            assertTrue(initialFailure.await(3, TimeUnit.SECONDS));

            CountDownLatch received = new CountDownLatch(1);
            CountDownLatch sent = new CountDownLatch(1);
            try (NettyClusterTransport sceneTransport = new NettyClusterTransport(endpoints::get, codecs, transportConfig)) {
                sceneTransport.bind(scene, ignored -> received.countDown());

                gameTransport.send(scene.id(), envelope(game, scene, 2), successCallback(sent));

                assertTrue(sent.await(3, TimeUnit.SECONDS));
                assertTrue(received.await(3, TimeUnit.SECONDS));
                assertEventually(() -> gameTransport.stats().activeConnections() == 1);
                assertEquals(2, gameTransport.stats().connectionAttempts());
                assertEquals(1, gameTransport.stats().connectionFailures());
                assertEquals(1, gameTransport.stats().sentEnvelopes());
                assertEquals(0, gameTransport.stats().inFlightWrites());
            }
        }
    }

    @Test
    void concurrentWritesShareOneAsynchronousConnectionAttempt() throws Exception {
        int gamePort = freePort();
        int scenePort = freePort();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", gamePort, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", scenePort, Set.of(SceneOperations.ENTER));
        Map<ServiceId, ServiceEndpoint> endpoints = Map.of(game.id(), game.endpoint(), scene.id(), scene.endpoint());
        PayloadCodecRegistry codecs = RegistryPayloadCodecs.registerTo(CrossPayloadCodecs.create());
        CountDownLatch received = new CountDownLatch(8);
        CountDownLatch sent = new CountDownLatch(8);

        try (NettyClusterTransport gameTransport = new NettyClusterTransport(endpoints::get, codecs);
             NettyClusterTransport sceneTransport = new NettyClusterTransport(endpoints::get, codecs)) {
            gameTransport.bind(game, ignored -> {
            });
            sceneTransport.bind(scene, ignored -> received.countDown());

            for (int requestId = 1; requestId <= 8; requestId++) {
                gameTransport.send(scene.id(), envelope(game, scene, requestId), successCallback(sent));
            }

            assertTrue(sent.await(3, TimeUnit.SECONDS));
            assertTrue(received.await(3, TimeUnit.SECONDS));
            assertEquals(1, gameTransport.stats().connectionAttempts());
            assertEquals(8, gameTransport.stats().sentEnvelopes());
            assertEquals(0, gameTransport.stats().inFlightWrites());
        }
    }

    @Test
    void reconnectsAfterEstablishedRemoteTransportStopsAndRestarts() throws Exception {
        int gamePort = freePort();
        int scenePort = freePort();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", gamePort, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", scenePort, Set.of(SceneOperations.ENTER));
        Map<ServiceId, ServiceEndpoint> endpoints = Map.of(game.id(), game.endpoint(), scene.id(), scene.endpoint());
        PayloadCodecRegistry codecs = RegistryPayloadCodecs.registerTo(CrossPayloadCodecs.create());
        NettyClusterTransportConfig config = new NettyClusterTransportConfig(
                Duration.ofSeconds(1), Duration.ZERO, 16);

        try (NettyClusterTransport gameTransport = new NettyClusterTransport(endpoints::get, codecs, config)) {
            gameTransport.bind(game, ignored -> {
            });
            NettyClusterTransport firstScene = new NettyClusterTransport(endpoints::get, codecs, config);
            CountDownLatch firstReceived = new CountDownLatch(1);
            firstScene.bind(scene, ignored -> firstReceived.countDown());
            gameTransport.send(scene.id(), envelope(game, scene, 1));
            assertTrue(firstReceived.await(3, TimeUnit.SECONDS));
            assertEventually(() -> gameTransport.stats().activeConnections() == 1);

            firstScene.close();
            assertEventually(() -> gameTransport.stats().activeConnections() == 0);

            CountDownLatch secondReceived = new CountDownLatch(1);
            CountDownLatch secondSent = new CountDownLatch(1);
            try (NettyClusterTransport restartedScene = new NettyClusterTransport(endpoints::get, codecs, config)) {
                restartedScene.bind(scene, ignored -> secondReceived.countDown());
                gameTransport.send(scene.id(), envelope(game, scene, 2), successCallback(secondSent));

                assertTrue(secondSent.await(3, TimeUnit.SECONDS));
                assertTrue(secondReceived.await(3, TimeUnit.SECONDS));
                assertEquals(2, gameTransport.stats().connectionAttempts());
                assertEquals(0, gameTransport.stats().connectionFailures());
                assertEquals(2, gameTransport.stats().sentEnvelopes());
            }
        }
    }

    private static ClusterEnvelope envelope(ServiceDescriptor source, ServiceDescriptor target, long requestId) {
        return new ClusterEnvelope(
                requestId,
                source.id(),
                target.id(),
                SceneOperations.ENTER,
                new EnterSceneRequest(10001L, "room-1")
        );
    }

    private static ClusterSendCallback successCallback(CountDownLatch sent) {
        return new ClusterSendCallback() {
            @Override
            public void success() {
                sent.countDown();
            }

            @Override
            public void failure(Throwable error) {
            }
        };
    }

    private static ClusterSendCallback failureCallback(
            AtomicReference<Throwable> failure,
            CountDownLatch failed
    ) {
        return new ClusterSendCallback() {
            @Override
            public void success() {
            }

            @Override
            public void failure(Throwable error) {
                failure.set(error);
                failed.countDown();
            }
        };
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> topics) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                topics,
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
