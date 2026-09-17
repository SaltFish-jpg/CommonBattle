package com.commonbattle.game.session;

import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.player.ActivityProgressCommand;
import com.commonbattle.game.player.PlayerBusinessCommandPayloadCodecs;
import com.commonbattle.game.player.PlayerBusinessOperations;
import com.commonbattle.game.player.NettyPlayerGatewayHandler;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PlayerClientCommandCodecTest {
    @Test
    void commandEnvelopeRoundTripsBusinessPayload() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoPlayerClientCommandCodec codec = new ProtoPlayerClientCommandCodec(registry);
        PlayerClientCommandEnvelope source = new PlayerClientCommandEnvelope(
                10001L,
                "session-1",
                1,
                7,
                PlayerBusinessOperations.ACTIVITY_PROGRESS,
                new ActivityProgressCommand("kill-3", 1)
        );

        PlayerClientCommandEnvelope decoded = codec.decode(codec.encode(source));

        assertEquals(source.playerId(), decoded.playerId());
        assertEquals(source.sessionId(), decoded.sessionId());
        assertEquals(source.sessionEpoch(), decoded.sessionEpoch());
        assertEquals(source.sequence(), decoded.sequence());
        assertEquals(source.operation(), decoded.operation());
        ActivityProgressCommand payload = assertInstanceOf(ActivityProgressCommand.class, decoded.payload());
        assertEquals("kill-3", payload.activityId());
        assertEquals(1, payload.delta());
    }

    @Test
    void inboundEnvelopeRoundTripsLoginTokenAndHeartbeat() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoPlayerClientInboundCodec codec = new ProtoPlayerClientInboundCodec(registry);

        PlayerClientInboundEnvelope login = codec.decode(codec.encode(PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1", "token-1")
        )));
        PlayerClientLoginRequest loginPayload = assertInstanceOf(PlayerClientLoginRequest.class, login.payload());
        assertEquals(PlayerClientInboundEnvelope.LOGIN, login.kind());
        assertEquals("token-1", loginPayload.token());

        PlayerClientInboundEnvelope heartbeat = codec.decode(codec.encode(PlayerClientInboundEnvelope.heartbeat(
                new PlayerClientHeartbeat(10001L, "session-1", 2, 9)
        )));
        PlayerClientHeartbeat heartbeatPayload = assertInstanceOf(PlayerClientHeartbeat.class, heartbeat.payload());
        assertEquals(PlayerClientInboundEnvelope.HEARTBEAT, heartbeat.kind());
        assertEquals(2, heartbeatPayload.sessionEpoch());
        assertEquals(9, heartbeatPayload.sequence());

        PlayerClientInboundEnvelope ack = codec.decode(codec.encode(PlayerClientInboundEnvelope.outboundAck(
                new PlayerClientOutboundAck(10001L, "session-1", 2, 11)
        )));
        PlayerClientOutboundAck ackPayload = assertInstanceOf(PlayerClientOutboundAck.class, ack.payload());
        assertEquals(PlayerClientInboundEnvelope.OUTBOUND_ACK, ack.kind());
        assertEquals(2, ackPayload.sessionEpoch());
        assertEquals(11, ackPayload.acknowledgedSequence());
    }

    @Test
    void clientEnvelopeRoundTripsLoginCodeAndRejectResponse() {
        PayloadCodecRegistry registry = PlayerClientPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoPlayerClientCodec codec = new ProtoPlayerClientCodec(registry);

        PlayerClientEnvelope login = codec.decode(codec.encode(new PlayerClientEnvelope(
                10001L,
                NettyPlayerGatewayHandler.LOGIN_RESPONSE_TOPIC,
                new PlayerClientLoginResponse(
                        10001L,
                        "session-1",
                        0,
                        false,
                        0,
                        0,
                        "FAILED",
                        "duplicate login rejected",
                        PlayerClientErrorCode.DUPLICATE_LOGIN
                ),
                1,
                Instant.parse("2026-09-01T00:00:00Z")
        )));
        PlayerClientLoginResponse loginPayload = assertInstanceOf(PlayerClientLoginResponse.class, login.payload());
        assertEquals(PlayerClientErrorCode.DUPLICATE_LOGIN, loginPayload.code());

        PlayerClientEnvelope reject = codec.decode(codec.encode(new PlayerClientEnvelope(
                10001L,
                NettyPlayerGatewayHandler.REJECT_TOPIC,
                new PlayerClientRejectResponse(
                        PlayerClientErrorCode.COMMAND_RATE_LIMITED,
                        "command rate limited",
                        1000,
                        true
                ),
                7,
                Instant.parse("2026-09-01T00:00:00Z")
        )));
        PlayerClientRejectResponse rejectPayload = assertInstanceOf(PlayerClientRejectResponse.class, reject.payload());
        assertEquals(PlayerClientErrorCode.COMMAND_RATE_LIMITED, rejectPayload.code());
        assertEquals("command rate limited", rejectPayload.message());
        assertEquals(1000, rejectPayload.retryAfterMillis());
        assertEquals(true, rejectPayload.closeConnection());
    }

    @Test
    void clientEnvelopeRoundTripsCommandResponsePayload() {
        PayloadCodecRegistry registry = PlayerClientPayloadCodecs.registerTo(
                PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults())
        );
        ProtoPlayerClientCodec codec = new ProtoPlayerClientCodec(registry);

        PlayerClientEnvelope decoded = codec.decode(codec.encode(new PlayerClientEnvelope(
                10001L,
                "player.command.response",
                new PlayerClientCommandResponse(
                        10001L,
                        "session-1",
                        1,
                        3,
                        PlayerBusinessOperations.ACTIVITY_PROGRESS,
                        com.commonbattle.game.player.PlayerBusinessResponseStatus.SUCCESS,
                        "OK",
                        "",
                        75,
                        true,
                        com.commonbattle.game.player.PlayerBusinessAck.OK
                ),
                3,
                Instant.parse("2026-09-01T00:00:00Z")
        )));

        PlayerClientCommandResponse response = assertInstanceOf(PlayerClientCommandResponse.class, decoded.payload());
        assertEquals(10001L, response.playerId());
        assertEquals(3, response.sequence());
        assertEquals(75, response.retryAfterMillis());
        assertEquals(true, response.replayed());
        assertEquals(com.commonbattle.game.player.PlayerBusinessAck.OK, response.payload());
    }
}
