package com.commonbattle.cluster.netty;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.registry.RegistryPayloadCodecs;
import com.commonbattle.example.cross.CrossPayloadCodecs;
import com.commonbattle.example.cross.EnterSceneRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
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
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", scenePort, Set.of("scene.enter"));
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
                    "scene.enter",
                    new EnterSceneRequest(10001L, "room-1")
            ));

            assertTrue(received.await(3, TimeUnit.SECONDS));
            ClusterEnvelope envelope = envelopeRef.get();
            assertEquals("scene.enter", envelope.operation());
            EnterSceneRequest payload = assertInstanceOf(EnterSceneRequest.class, envelope.payload());
            assertEquals("room-1", payload.sceneId());
            assertEventually(() -> gameTransport.stats().sentEnvelopes() == 1);
            assertEquals(1, gameTransport.stats().activeConnections());
            assertEquals(1, gameTransport.stats().connectionAttempts());
            assertEquals(0, gameTransport.stats().connectionFailures());
            assertEquals(1, sceneTransport.stats().receivedEnvelopes());
        }
    }

    @Test
    void recordsConnectionFailureWhenTargetIsUnavailable() throws Exception {
        int gamePort = freePort();
        int missingScenePort = freePort();
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", gamePort, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", missingScenePort, Set.of("scene.enter"));
        Map<ServiceId, ServiceEndpoint> endpoints = Map.of(
                game.id(), game.endpoint(),
                scene.id(), scene.endpoint()
        );
        PayloadCodecRegistry codecs = RegistryPayloadCodecs.registerTo(CrossPayloadCodecs.create());

        try (NettyClusterTransport gameTransport = new NettyClusterTransport(endpoints::get, codecs)) {
            gameTransport.bind(game, ignored -> {
            });

            assertThrows(IllegalStateException.class, () -> gameTransport.send(scene.id(), new ClusterEnvelope(
                    1,
                    game.id(),
                    scene.id(),
                    "scene.enter",
                    new EnterSceneRequest(10001L, "room-1")
            )));

            assertEquals(1, gameTransport.stats().connectionAttempts());
            assertEquals(1, gameTransport.stats().connectionFailures());
            assertEquals(0, gameTransport.stats().activeConnections());
            assertEquals(0, gameTransport.stats().sentEnvelopes());
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
