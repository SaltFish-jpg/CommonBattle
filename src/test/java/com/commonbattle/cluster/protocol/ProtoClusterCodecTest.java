package com.commonbattle.cluster.protocol;

import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.cross.CombatDamageRequest;
import com.commonbattle.example.cross.CombatDamageResult;
import com.commonbattle.example.cross.CrossPayloadCodecs;
import com.commonbattle.example.cross.EnterSceneRequest;
import com.commonbattle.example.cross.LeaveSceneRequest;
import com.commonbattle.example.cross.LeaveSceneResult;
import com.commonbattle.example.cross.MoveSceneRequest;
import com.commonbattle.example.cross.MoveSceneResult;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.game.player.ActivityProgressCommand;
import com.commonbattle.game.player.PlayerBusinessAck;
import com.commonbattle.game.player.PlayerBusinessCommandPayloadCodecs;
import com.commonbattle.game.player.PlayerBusinessOperations;
import com.commonbattle.game.player.PlayerBusinessResponse;
import com.commonbattle.game.player.PlayerBusinessResponseStatus;
import com.commonbattle.game.player.PlayerBusinessRpcOperations;
import com.commonbattle.game.session.PlayerCommand;
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
                SceneOperations.ENTER,
                new EnterSceneRequest(10001L, "room-1", 12, -4)
        );

        ClusterEnvelope decoded = codec.decode(codec.encode(envelope));

        assertEquals(envelope.requestId(), decoded.requestId());
        assertEquals(envelope.source(), decoded.source());
        assertEquals(envelope.target(), decoded.target());
        assertEquals(envelope.operation(), decoded.operation());
        EnterSceneRequest payload = assertInstanceOf(EnterSceneRequest.class, decoded.payload());
        assertEquals(10001L, payload.playerId());
        assertEquals("room-1", payload.sceneId());
        assertEquals(12, payload.chunkX());
        assertEquals(-4, payload.chunkY());
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
                SceneOperations.ENTER,
                new EnterSceneRequest(10001L, "room-1"),
                Map.of("rpc.idempotency_key", "player-10001-enter-room-1")
        );

        ClusterEnvelope decoded = codec.decode(codec.encode(envelope));

        assertEquals("player-10001-enter-room-1", decoded.metadata().get("rpc.idempotency_key"));
    }

    @Test
    void encodesLeaveScenePayloads() {
        ProtoClusterCodec codec = new ProtoClusterCodec(CrossPayloadCodecs.create());
        ClusterEnvelope request = new ClusterEnvelope(
                10,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                SceneOperations.LEAVE,
                new LeaveSceneRequest(10001L, "room-1")
        );
        ClusterEnvelope response = new ClusterEnvelope(
                10,
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                "$rpc.success",
                new LeaveSceneResult(10001L, "room-1", true)
        );

        ClusterEnvelope decodedRequest = codec.decode(codec.encode(request));
        ClusterEnvelope decodedResponse = codec.decode(codec.encode(response));

        LeaveSceneRequest requestPayload = assertInstanceOf(LeaveSceneRequest.class, decodedRequest.payload());
        LeaveSceneResult responsePayload = assertInstanceOf(LeaveSceneResult.class, decodedResponse.payload());
        assertEquals(10001L, requestPayload.playerId());
        assertEquals("room-1", requestPayload.sceneId());
        assertEquals(true, responsePayload.left());
    }

    @Test
    void encodesMoveScenePayloads() {
        ProtoClusterCodec codec = new ProtoClusterCodec(CrossPayloadCodecs.create());
        ClusterEnvelope request = new ClusterEnvelope(
                13,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                SceneOperations.MOVE,
                new MoveSceneRequest(10001L, "world-1", 12, -4)
        );
        ClusterEnvelope response = new ClusterEnvelope(
                13,
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                "$rpc.success",
                new MoveSceneResult(10001L, "world-1", 12, -4, 2, 5, true,
                        java.util.List.of(20002L),
                        java.util.List.of(30003L),
                        java.util.List.of(20002L),
                        java.util.List.of(30003L))
        );

        ClusterEnvelope decodedRequest = codec.decode(codec.encode(request));
        ClusterEnvelope decodedResponse = codec.decode(codec.encode(response));

        MoveSceneRequest requestPayload = assertInstanceOf(MoveSceneRequest.class, decodedRequest.payload());
        MoveSceneResult responsePayload = assertInstanceOf(MoveSceneResult.class, decodedResponse.payload());
        assertEquals(10001L, requestPayload.playerId());
        assertEquals("world-1", requestPayload.sceneId());
        assertEquals(12, requestPayload.chunkX());
        assertEquals(-4, requestPayload.chunkY());
        assertEquals(2, responsePayload.previousShardIndex());
        assertEquals(5, responsePayload.shardIndex());
        assertEquals(true, responsePayload.moved());
        assertEquals(java.util.List.of(20002L), responsePayload.visibleEnter());
        assertEquals(java.util.List.of(30003L), responsePayload.visibleLeave());
        assertEquals(java.util.List.of(20002L), responsePayload.observerEnter());
        assertEquals(java.util.List.of(30003L), responsePayload.observerLeave());
    }

    @Test
    void encodesCombatDamagePayloads() {
        ProtoClusterCodec codec = new ProtoClusterCodec(CrossPayloadCodecs.create());
        ClusterEnvelope request = new ClusterEnvelope(
                14,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                SceneOperations.COMBAT_DAMAGE,
                new CombatDamageRequest("world-1", 10001L, 20002L, 35)
        );
        ClusterEnvelope response = new ClusterEnvelope(
                14,
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                "$rpc.success",
                new CombatDamageResult("world-1", 10001L, 20002L, 3, 35, 80, 45, true)
        );

        ClusterEnvelope decodedRequest = codec.decode(codec.encode(request));
        ClusterEnvelope decodedResponse = codec.decode(codec.encode(response));

        CombatDamageRequest requestPayload = assertInstanceOf(CombatDamageRequest.class, decodedRequest.payload());
        CombatDamageResult responsePayload = assertInstanceOf(CombatDamageResult.class, decodedResponse.payload());
        assertEquals("world-1", requestPayload.sceneId());
        assertEquals(10001L, requestPayload.attackerId());
        assertEquals(20002L, requestPayload.targetId());
        assertEquals(35, requestPayload.damage());
        assertEquals(true, responsePayload.applied());
        assertEquals(45, responsePayload.hpAfter());
    }

    @Test
    void encodesStructuredRpcErrorPayload() {
        ProtoClusterCodec codec = new ProtoClusterCodec(CrossPayloadCodecs.create());
        ClusterEnvelope response = new ClusterEnvelope(
                12,
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                "$rpc.failure",
                new ClusterRpcGateway.RpcError("mailbox_pressure:target", "mailbox_pressure:target", 50)
        );

        ClusterEnvelope decoded = codec.decode(codec.encode(response));

        ClusterRpcGateway.RpcError error = assertInstanceOf(ClusterRpcGateway.RpcError.class, decoded.payload());
        assertEquals("mailbox_pressure:target", error.code());
        assertEquals("mailbox_pressure:target", error.message());
        assertEquals(50, error.retryAfterMillis());
    }

    @Test
    void encodesPlayerBusinessCommandAndResponseEnvelope() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        ClusterEnvelope request = new ClusterEnvelope(
                11,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-2"),
                PlayerBusinessRpcOperations.DISPATCH,
                new PlayerCommand(
                        10001L,
                        "session-1",
                        1,
                        1,
                        PlayerBusinessOperations.ACTIVITY_PROGRESS,
                        new ActivityProgressCommand("kill-3", 1)
                )
        );
        ClusterEnvelope response = new ClusterEnvelope(
                11,
                ServiceId.of(ServiceKind.GAME, "r1", "game-2"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                "$rpc.success",
                new PlayerBusinessResponse(
                        10001L,
                        "session-1",
                        1,
                        1,
                        PlayerBusinessOperations.ACTIVITY_PROGRESS,
                        PlayerBusinessResponseStatus.SUCCESS,
                        PlayerBusinessResponse.OK,
                        "",
                        PlayerBusinessAck.OK
                )
        );

        ClusterEnvelope decodedRequest = codec.decode(codec.encode(request));
        ClusterEnvelope decodedResponse = codec.decode(codec.encode(response));

        PlayerCommand command = assertInstanceOf(PlayerCommand.class, decodedRequest.payload());
        ActivityProgressCommand payload = assertInstanceOf(ActivityProgressCommand.class, command.payload());
        PlayerBusinessResponse businessResponse = assertInstanceOf(PlayerBusinessResponse.class, decodedResponse.payload());
        assertEquals(PlayerBusinessRpcOperations.DISPATCH, decodedRequest.operation());
        assertEquals(PlayerBusinessOperations.ACTIVITY_PROGRESS, command.operation());
        assertEquals("kill-3", payload.activityId());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, businessResponse.status());
        assertEquals(PlayerBusinessAck.OK, businessResponse.payload());
    }

    public static class PrototypeMoveRequest {
        public long playerId;
        public int x;
        public int y;
    }
}
