package com.commonbattle.game.session;

import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.util.Objects;

/**
 * 玩家客户端入站统一信封 protobuf 编解码。
 * 登录帧和命令帧共享外层 kind，便于同一条 Netty 连接在登录后继续收业务命令。
 */
public final class ProtoPlayerClientInboundCodec {
    private final ProtoPlayerClientCommandCodec commandCodec;

    public ProtoPlayerClientInboundCodec(PayloadCodecRegistry payloadCodecs) {
        this.commandCodec = new ProtoPlayerClientCommandCodec(Objects.requireNonNull(payloadCodecs, "payloadCodecs"));
    }

    public byte[] encode(PlayerClientInboundEnvelope envelope) {
        byte[] payloadBytes = encodePayload(envelope);
        int size = CodedOutputStream.computeStringSize(1, envelope.kind())
                + CodedOutputStream.computeByteArraySize(2, payloadBytes);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, envelope.kind());
            output.writeByteArray(2, payloadBytes);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player inbound envelope", e);
        }
    }

    public PlayerClientInboundEnvelope decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String kind = "";
        byte[] payloadBytes = new byte[0];
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> kind = input.readString();
                    case 2 -> payloadBytes = input.readByteArray();
                    default -> input.skipField(tag);
                }
            }
            return switch (kind) {
                case PlayerClientInboundEnvelope.LOGIN ->
                        PlayerClientInboundEnvelope.login(decodeLogin(payloadBytes));
                case PlayerClientInboundEnvelope.COMMAND ->
                        PlayerClientInboundEnvelope.command(commandCodec.decode(payloadBytes));
                case PlayerClientInboundEnvelope.HEARTBEAT ->
                        PlayerClientInboundEnvelope.heartbeat(decodeHeartbeat(payloadBytes));
                case PlayerClientInboundEnvelope.OUTBOUND_ACK ->
                        PlayerClientInboundEnvelope.outboundAck(decodeOutboundAck(payloadBytes));
                default -> throw new IllegalArgumentException("Unknown player inbound kind " + kind);
            };
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player inbound envelope", e);
        }
    }

    private byte[] encodePayload(PlayerClientInboundEnvelope envelope) {
        return switch (envelope.kind()) {
            case PlayerClientInboundEnvelope.LOGIN ->
                    encodeLogin((PlayerClientLoginRequest) envelope.payload());
            case PlayerClientInboundEnvelope.COMMAND ->
                    commandCodec.encode((PlayerClientCommandEnvelope) envelope.payload());
            case PlayerClientInboundEnvelope.HEARTBEAT ->
                    encodeHeartbeat((PlayerClientHeartbeat) envelope.payload());
            case PlayerClientInboundEnvelope.OUTBOUND_ACK ->
                    encodeOutboundAck((PlayerClientOutboundAck) envelope.payload());
            default -> throw new IllegalArgumentException("Unknown player inbound kind " + envelope.kind());
        };
    }

    private static byte[] encodeLogin(PlayerClientLoginRequest request) {
        int size = CodedOutputStream.computeInt64Size(1, request.playerId())
                + CodedOutputStream.computeStringSize(2, request.sessionId())
                + CodedOutputStream.computeStringSize(3, request.token());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, request.playerId());
            output.writeString(2, request.sessionId());
            output.writeString(3, request.token());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player login request", e);
        }
    }

    private static PlayerClientLoginRequest decodeLogin(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long playerId = 0;
        String sessionId = "";
        String token = "";
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> playerId = input.readInt64();
                    case 2 -> sessionId = input.readString();
                    case 3 -> token = input.readString();
                    default -> input.skipField(tag);
                }
            }
            return new PlayerClientLoginRequest(playerId, sessionId, token);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player login request", e);
        }
    }

    private static byte[] encodeHeartbeat(PlayerClientHeartbeat heartbeat) {
        int size = CodedOutputStream.computeInt64Size(1, heartbeat.playerId())
                + CodedOutputStream.computeStringSize(2, heartbeat.sessionId())
                + CodedOutputStream.computeInt64Size(3, heartbeat.sessionEpoch())
                + CodedOutputStream.computeInt64Size(4, heartbeat.sequence());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, heartbeat.playerId());
            output.writeString(2, heartbeat.sessionId());
            output.writeInt64(3, heartbeat.sessionEpoch());
            output.writeInt64(4, heartbeat.sequence());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player heartbeat", e);
        }
    }

    private static PlayerClientHeartbeat decodeHeartbeat(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long playerId = 0;
        String sessionId = "";
        long sessionEpoch = 0;
        long sequence = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> playerId = input.readInt64();
                    case 2 -> sessionId = input.readString();
                    case 3 -> sessionEpoch = input.readInt64();
                    case 4 -> sequence = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return new PlayerClientHeartbeat(playerId, sessionId, sessionEpoch, sequence);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player heartbeat", e);
        }
    }

    private static byte[] encodeOutboundAck(PlayerClientOutboundAck ack) {
        int size = CodedOutputStream.computeInt64Size(1, ack.playerId())
                + CodedOutputStream.computeStringSize(2, ack.sessionId())
                + CodedOutputStream.computeInt64Size(3, ack.sessionEpoch())
                + CodedOutputStream.computeInt64Size(4, ack.acknowledgedSequence());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, ack.playerId());
            output.writeString(2, ack.sessionId());
            output.writeInt64(3, ack.sessionEpoch());
            output.writeInt64(4, ack.acknowledgedSequence());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player outbound ack", e);
        }
    }

    private static PlayerClientOutboundAck decodeOutboundAck(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long playerId = 0;
        String sessionId = "";
        long sessionEpoch = 0;
        long acknowledgedSequence = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> playerId = input.readInt64();
                    case 2 -> sessionId = input.readString();
                    case 3 -> sessionEpoch = input.readInt64();
                    case 4 -> acknowledgedSequence = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return new PlayerClientOutboundAck(playerId, sessionId, sessionEpoch, acknowledgedSequence);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player outbound ack", e);
        }
    }
}
