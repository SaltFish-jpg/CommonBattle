package com.commonbattle.game.session;

import com.commonbattle.cluster.protocol.PayloadCodec;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadEncoding;
import com.commonbattle.game.player.PlayerBusinessResponseStatus;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;

/**
 * 玩家客户端公共 payload codec。
 */
public final class PlayerClientPayloadCodecs {
    private PlayerClientPayloadCodecs() {
    }

    public static PayloadCodecRegistry registerTo(PayloadCodecRegistry registry) {
        registry.register(new LoginResponseCodec());
        registry.register(new HeartbeatAckCodec());
        registry.register(new RejectResponseCodec());
        registry.register(new CommandResponseCodec(registry));
        return registry;
    }

    private static final class LoginResponseCodec implements PayloadCodec<PlayerClientLoginResponse> {
        @Override
        public String typeName() {
            return PlayerClientLoginResponse.class.getName();
        }

        @Override
        public Class<PlayerClientLoginResponse> javaType() {
            return PlayerClientLoginResponse.class;
        }

        @Override
        public byte[] encode(PlayerClientLoginResponse payload) {
            int size = CodedOutputStream.computeInt64Size(1, payload.playerId())
                    + CodedOutputStream.computeStringSize(2, payload.sessionId())
                    + CodedOutputStream.computeInt64Size(3, payload.sessionEpoch())
                    + CodedOutputStream.computeBoolSize(4, payload.created())
                    + CodedOutputStream.computeInt64Size(5, payload.stateRevision())
                    + CodedOutputStream.computeInt64Size(6, payload.eventRevision())
                    + CodedOutputStream.computeStringSize(7, payload.status())
                    + CodedOutputStream.computeStringSize(8, payload.message())
                    + CodedOutputStream.computeStringSize(9, payload.code().name());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, payload.playerId());
                output.writeString(2, payload.sessionId());
                output.writeInt64(3, payload.sessionEpoch());
                output.writeBool(4, payload.created());
                output.writeInt64(5, payload.stateRevision());
                output.writeInt64(6, payload.eventRevision());
                output.writeString(7, payload.status());
                output.writeString(8, payload.message());
                output.writeString(9, payload.code().name());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player login response", e);
            }
        }

        @Override
        public PlayerClientLoginResponse decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long playerId = 0;
            String sessionId = "";
            long sessionEpoch = 0;
            boolean created = false;
            long stateRevision = 0;
            long eventRevision = 0;
            String status = "";
            String message = "";
            PlayerClientErrorCode code = PlayerClientErrorCode.LOGIN_FAILED;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> playerId = input.readInt64();
                        case 2 -> sessionId = input.readString();
                        case 3 -> sessionEpoch = input.readInt64();
                        case 4 -> created = input.readBool();
                        case 5 -> stateRevision = input.readInt64();
                        case 6 -> eventRevision = input.readInt64();
                        case 7 -> status = input.readString();
                        case 8 -> message = input.readString();
                        case 9 -> code = PlayerClientErrorCode.valueOf(input.readString());
                        default -> input.skipField(tag);
                    }
                }
                if ("OK".equals(status)) {
                    code = PlayerClientErrorCode.OK;
                }
                return new PlayerClientLoginResponse(
                        playerId,
                        sessionId,
                        sessionEpoch,
                        created,
                        stateRevision,
                        eventRevision,
                        status,
                        message,
                        code
                );
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player login response", e);
            }
        }
    }

    private static final class RejectResponseCodec implements PayloadCodec<PlayerClientRejectResponse> {
        @Override
        public String typeName() {
            return PlayerClientRejectResponse.class.getName();
        }

        @Override
        public Class<PlayerClientRejectResponse> javaType() {
            return PlayerClientRejectResponse.class;
        }

        @Override
        public byte[] encode(PlayerClientRejectResponse payload) {
            int size = CodedOutputStream.computeStringSize(1, payload.code().name())
                    + CodedOutputStream.computeStringSize(2, payload.message())
                    + CodedOutputStream.computeBoolSize(3, payload.closeConnection());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeString(1, payload.code().name());
                output.writeString(2, payload.message());
                output.writeBool(3, payload.closeConnection());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player reject response", e);
            }
        }

        @Override
        public PlayerClientRejectResponse decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            PlayerClientErrorCode code = PlayerClientErrorCode.INVALID_FRAME;
            String message = "";
            boolean closeConnection = true;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> code = PlayerClientErrorCode.valueOf(input.readString());
                        case 2 -> message = input.readString();
                        case 3 -> closeConnection = input.readBool();
                        default -> input.skipField(tag);
                    }
                }
                return new PlayerClientRejectResponse(code, message, closeConnection);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player reject response", e);
            }
        }
    }

    private static final class CommandResponseCodec implements PayloadCodec<PlayerClientCommandResponse> {
        private final PayloadCodecRegistry registry;

        private CommandResponseCodec(PayloadCodecRegistry registry) {
            this.registry = registry;
        }

        @Override
        public String typeName() {
            return PlayerClientCommandResponse.class.getName();
        }

        @Override
        public Class<PlayerClientCommandResponse> javaType() {
            return PlayerClientCommandResponse.class;
        }

        @Override
        public byte[] encode(PlayerClientCommandResponse payload) {
            EncodedPayload nested = registry.encode(payload.payload());
            int size = CodedOutputStream.computeInt64Size(1, payload.playerId())
                    + CodedOutputStream.computeStringSize(2, payload.sessionId())
                    + CodedOutputStream.computeInt64Size(3, payload.sessionEpoch())
                    + CodedOutputStream.computeInt64Size(4, payload.sequence())
                    + CodedOutputStream.computeStringSize(5, payload.operation())
                    + CodedOutputStream.computeStringSize(6, payload.status().name())
                    + CodedOutputStream.computeStringSize(7, payload.code())
                    + CodedOutputStream.computeStringSize(8, payload.message())
                    + CodedOutputStream.computeBoolSize(9, payload.replayed())
                    + CodedOutputStream.computeStringSize(10, nested.codecName())
                    + CodedOutputStream.computeStringSize(11, nested.typeName())
                    + CodedOutputStream.computeByteArraySize(12, nested.bytes());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, payload.playerId());
                output.writeString(2, payload.sessionId());
                output.writeInt64(3, payload.sessionEpoch());
                output.writeInt64(4, payload.sequence());
                output.writeString(5, payload.operation());
                output.writeString(6, payload.status().name());
                output.writeString(7, payload.code());
                output.writeString(8, payload.message());
                output.writeBool(9, payload.replayed());
                output.writeString(10, nested.codecName());
                output.writeString(11, nested.typeName());
                output.writeByteArray(12, nested.bytes());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player client command response", e);
            }
        }

        @Override
        public PlayerClientCommandResponse decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long playerId = 0;
            String sessionId = "";
            long sessionEpoch = 0;
            long sequence = 0;
            String operation = "";
            PlayerBusinessResponseStatus status = PlayerBusinessResponseStatus.SUCCESS;
            String code = "";
            String message = "";
            boolean replayed = false;
            String payloadCodecName = PayloadEncoding.NONE;
            String payloadTypeName = "";
            byte[] payloadBytes = new byte[0];
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> playerId = input.readInt64();
                        case 2 -> sessionId = input.readString();
                        case 3 -> sessionEpoch = input.readInt64();
                        case 4 -> sequence = input.readInt64();
                        case 5 -> operation = input.readString();
                        case 6 -> status = PlayerBusinessResponseStatus.valueOf(input.readString());
                        case 7 -> code = input.readString();
                        case 8 -> message = input.readString();
                        case 9 -> replayed = input.readBool();
                        case 10 -> payloadCodecName = input.readString();
                        case 11 -> payloadTypeName = input.readString();
                        case 12 -> payloadBytes = input.readByteArray();
                        default -> input.skipField(tag);
                    }
                }
                return new PlayerClientCommandResponse(
                        playerId,
                        sessionId,
                        sessionEpoch,
                        sequence,
                        operation,
                        status,
                        code,
                        message,
                        replayed,
                        registry.decode(payloadCodecName, payloadTypeName, payloadBytes)
                );
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player client command response", e);
            }
        }
    }

    private static final class HeartbeatAckCodec implements PayloadCodec<PlayerClientHeartbeatAck> {
        @Override
        public String typeName() {
            return PlayerClientHeartbeatAck.class.getName();
        }

        @Override
        public Class<PlayerClientHeartbeatAck> javaType() {
            return PlayerClientHeartbeatAck.class;
        }

        @Override
        public byte[] encode(PlayerClientHeartbeatAck payload) {
            int size = CodedOutputStream.computeInt64Size(1, payload.playerId())
                    + CodedOutputStream.computeStringSize(2, payload.sessionId())
                    + CodedOutputStream.computeInt64Size(3, payload.sessionEpoch())
                    + CodedOutputStream.computeInt64Size(4, payload.sequence())
                    + CodedOutputStream.computeStringSize(5, payload.status());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, payload.playerId());
                output.writeString(2, payload.sessionId());
                output.writeInt64(3, payload.sessionEpoch());
                output.writeInt64(4, payload.sequence());
                output.writeString(5, payload.status());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player heartbeat ack", e);
            }
        }

        @Override
        public PlayerClientHeartbeatAck decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long playerId = 0;
            String sessionId = "";
            long sessionEpoch = 0;
            long sequence = 0;
            String status = "";
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> playerId = input.readInt64();
                        case 2 -> sessionId = input.readString();
                        case 3 -> sessionEpoch = input.readInt64();
                        case 4 -> sequence = input.readInt64();
                        case 5 -> status = input.readString();
                        default -> input.skipField(tag);
                    }
                }
                return new PlayerClientHeartbeatAck(playerId, sessionId, sessionEpoch, sequence, status);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player heartbeat ack", e);
            }
        }
    }
}
