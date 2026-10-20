package com.commonbattle.game.player;

import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 完成响应检查点的 protobuf 编解码器。
 * 响应信封及其业务 payload 复用跨服协议注册表，避免持久化与网络使用两套类型契约。
 */
public final class PlayerBusinessResponseSnapshotCodec {
    private final PayloadCodecRegistry payloadCodecs;

    public PlayerBusinessResponseSnapshotCodec(PayloadCodecRegistry payloadCodecs) {
        this.payloadCodecs = Objects.requireNonNull(payloadCodecs, "payloadCodecs");
    }

    public byte[] encode(PlayerBusinessResponseSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<byte[]> responses = snapshot.responses().stream().map(this::encodeResponse).toList();
        int size = CodedOutputStream.computeInt64Size(1, snapshot.playerId());
        for (byte[] response : responses) {
            size += CodedOutputStream.computeByteArraySize(2, response);
        }
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, snapshot.playerId());
            for (byte[] response : responses) {
                output.writeByteArray(2, response);
            }
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player business response snapshot", e);
        }
    }

    public PlayerBusinessResponseSnapshot decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long playerId = 0;
        List<PlayerBusinessResponse> responses = new ArrayList<>();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> playerId = input.readInt64();
                    case 2 -> responses.add(decodeResponse(input.readByteArray()));
                    default -> input.skipField(tag);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player business response snapshot", e);
        }
        return new PlayerBusinessResponseSnapshot(playerId, responses);
    }

    private byte[] encodeResponse(PlayerBusinessResponse response) {
        EncodedPayload encoded = payloadCodecs.encode(response);
        int size = CodedOutputStream.computeStringSize(1, encoded.codecName())
                + CodedOutputStream.computeStringSize(2, encoded.typeName())
                + CodedOutputStream.computeByteArraySize(3, encoded.bytes());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, encoded.codecName());
            output.writeString(2, encoded.typeName());
            output.writeByteArray(3, encoded.bytes());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode completed player response", e);
        }
    }

    private PlayerBusinessResponse decodeResponse(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String codecName = "";
        String typeName = "";
        byte[] payloadBytes = new byte[0];
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> codecName = input.readString();
                    case 2 -> typeName = input.readString();
                    case 3 -> payloadBytes = input.readByteArray();
                    default -> input.skipField(tag);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode completed player response", e);
        }
        Object decoded = payloadCodecs.decode(codecName, typeName, payloadBytes);
        if (!(decoded instanceof PlayerBusinessResponse response)) {
            throw new IllegalArgumentException("checkpoint payload is not PlayerBusinessResponse: " + typeName);
        }
        return response;
    }
}
