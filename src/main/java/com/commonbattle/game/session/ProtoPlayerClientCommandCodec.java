package com.commonbattle.game.session;

import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.PayloadEncoding;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.util.Objects;

/**
 * 玩家客户端入站命令 protobuf 编解码。
 * 字段号只追加不复用，payload 类型由业务 codec 注册表显式控制。
 */
public final class ProtoPlayerClientCommandCodec {
    private final PayloadCodecRegistry payloadCodecs;

    public ProtoPlayerClientCommandCodec(PayloadCodecRegistry payloadCodecs) {
        this.payloadCodecs = Objects.requireNonNull(payloadCodecs, "payloadCodecs");
    }

    public byte[] encode(PlayerClientCommandEnvelope envelope) {
        EncodedPayload payload = payloadCodecs.encode(envelope.payload());
        int size = CodedOutputStream.computeInt64Size(1, envelope.playerId())
                + CodedOutputStream.computeStringSize(2, envelope.sessionId())
                + CodedOutputStream.computeInt64Size(3, envelope.sessionEpoch())
                + CodedOutputStream.computeInt64Size(4, envelope.sequence())
                + CodedOutputStream.computeStringSize(5, envelope.operation())
                + CodedOutputStream.computeStringSize(6, payload.codecName())
                + CodedOutputStream.computeStringSize(7, payload.typeName())
                + CodedOutputStream.computeByteArraySize(8, payload.bytes());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, envelope.playerId());
            output.writeString(2, envelope.sessionId());
            output.writeInt64(3, envelope.sessionEpoch());
            output.writeInt64(4, envelope.sequence());
            output.writeString(5, envelope.operation());
            output.writeString(6, payload.codecName());
            output.writeString(7, payload.typeName());
            output.writeByteArray(8, payload.bytes());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player client command", e);
        }
    }

    public PlayerClientCommandEnvelope decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long playerId = 0;
        String sessionId = "";
        long sessionEpoch = 0;
        long sequence = 0;
        String operation = "";
        String payloadCodec = PayloadEncoding.PROTOBUF;
        String payloadType = "";
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
                    case 6 -> payloadCodec = input.readString();
                    case 7 -> payloadType = input.readString();
                    case 8 -> payloadBytes = input.readByteArray();
                    default -> input.skipField(tag);
                }
            }
            return new PlayerClientCommandEnvelope(
                    playerId,
                    sessionId,
                    sessionEpoch,
                    sequence,
                    operation,
                    payloadCodecs.decode(payloadCodec, payloadType, payloadBytes)
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player client command", e);
        }
    }
}
