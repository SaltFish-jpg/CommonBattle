package com.commonbattle.cluster.protocol;

import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.example.cross.CrossPayloadCodecs;
import com.commonbattle.example.cross.EnterSceneRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ProtoClusterCodecTest {
    @Test
    void encodesEnvelopeWithRegisteredPayloadCodec() {
        ProtoClusterCodec codec = new ProtoClusterCodec(CrossPayloadCodecs.create());
        ClusterEnvelope envelope = new ClusterEnvelope(
                7,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                "scene.enter",
                new EnterSceneRequest(10001L, "room-1")
        );

        ClusterEnvelope decoded = codec.decode(codec.encode(envelope));

        assertEquals(envelope.requestId(), decoded.requestId());
        assertEquals(envelope.source(), decoded.source());
        assertEquals(envelope.target(), decoded.target());
        assertEquals(envelope.operation(), decoded.operation());
        EnterSceneRequest payload = assertInstanceOf(EnterSceneRequest.class, decoded.payload());
        assertEquals(10001L, payload.playerId());
        assertEquals("room-1", payload.sceneId());
    }

    @Test
    void envelopeCanCarryProtostuffBeanPayload() {
        PayloadCodecRegistry registry = PayloadCodecRegistry.commonDefaults();
        registry.registerProtostuffBean(PrototypeMoveRequest.class);
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        PrototypeMoveRequest request = new PrototypeMoveRequest();
        request.playerId = 10001L;
        request.x = 12;
        request.y = 8;
        ClusterEnvelope envelope = new ClusterEnvelope(
                8,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                "prototype.move",
                request
        );

        ClusterEnvelope decoded = codec.decode(codec.encode(envelope));

        PrototypeMoveRequest payload = assertInstanceOf(PrototypeMoveRequest.class, decoded.payload());
        assertEquals(10001L, payload.playerId);
        assertEquals(12, payload.x);
        assertEquals(8, payload.y);
    }

    @Test
    void envelopeMetadataSurvivesProtobufRoundTrip() {
        ProtoClusterCodec codec = new ProtoClusterCodec(CrossPayloadCodecs.create());
        ClusterEnvelope envelope = new ClusterEnvelope(
                9,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                "scene.enter",
                new EnterSceneRequest(10001L, "room-1"),
                Map.of("rpc.idempotency_key", "player-10001-enter-room-1")
        );

        ClusterEnvelope decoded = codec.decode(codec.encode(envelope));

        assertEquals("player-10001-enter-room-1", decoded.metadata().get("rpc.idempotency_key"));
    }

    public static class PrototypeMoveRequest {
        public long playerId;
        public int x;
        public int y;
    }
}
