package com.commonbattle.game.session;

import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.PayloadEncoding;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;

/**
 * 玩家客户端出站信封 protobuf 编解码。
 * 字段号只追加不复用，便于客户端灰度、回滚和跨版本短期共存。
 */
public final class ProtoPlayerClientCodec {
    private final PayloadCodecRegistry payloadCodecs;

    public ProtoPlayerClientCodec(PayloadCodecRegistry payloadCodecs) {
        this.payloadCodecs = Objects.requireNonNull(payloadCodecs, "payloadCodecs");
    }

    public byte[] encode(PlayerClientEnvelope envelope) {
        EncodedPayload payload = payloadCodecs.encode(envelope.payload());
        int size = CodedOutputStream.computeInt64Size(1, envelope.playerId())
                + CodedOutputStream.computeStringSize(2, envelope.topic())
                + CodedOutputStream.computeInt64Size(3, envelope.sequence())
                + CodedOutputStream.computeInt64Size(4, envelope.createdAt().toEpochMilli())
                + CodedOutputStream.computeStringSize(5, payload.codecName())
                + CodedOutputStream.computeStringSize(6, payload.typeName())
                + CodedOutputStream.computeByteArraySize(7, payload.bytes());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, envelope.playerId());
            output.writeString(2, envelope.topic());
            output.writeInt64(3, envelope.sequence());
            output.writeInt64(4, envelope.createdAt().toEpochMilli());
            output.writeString(5, payload.codecName());
            output.writeString(6, payload.typeName());
            output.writeByteArray(7, payload.bytes());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player client envelope", e);
        }
    }

    public PlayerClientEnvelope decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long playerId = 0;
        String topic = "";
        long sequence = 0;
        long createdAtMillis = 0;
        String payloadCodec = PayloadEncoding.PROTOBUF;
        String payloadType = "";
        byte[] payloadBytes = new byte[0];
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> playerId = input.readInt64();
                    case 2 -> topic = input.readString();
                    case 3 -> sequence = input.readInt64();
                    case 4 -> createdAtMillis = input.readInt64();
                    case 5 -> payloadCodec = input.readString();
                    case 6 -> payloadType = input.readString();
                    case 7 -> payloadBytes = input.readByteArray();
                    default -> input.skipField(tag);
                }
            }
            return new PlayerClientEnvelope(
                    playerId,
                    topic,
                    payloadCodecs.decode(payloadCodec, payloadType, payloadBytes),
                    sequence,
                    Instant.ofEpochMilli(createdAtMillis)
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player client envelope", e);
        }
    }
}
