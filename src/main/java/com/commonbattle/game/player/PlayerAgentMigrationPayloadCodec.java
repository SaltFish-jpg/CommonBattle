package com.commonbattle.game.player;

import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.util.Objects;

/**
 * 玩家迁移 v2 载荷编解码器。
 * 外层字段遵循 protobuf 尾后追加规则，业务响应复用跨服协议注册表，保证灰度节点使用同一套 payload 契约。
 */
public final class PlayerAgentMigrationPayloadCodec {
    private final PlayerStateSnapshotSerializer stateSerializer;
    private final PlayerBusinessResponseSnapshotCodec responseCodec;

    public PlayerAgentMigrationPayloadCodec(
            PlayerStateSnapshotSerializer stateSerializer,
            PayloadCodecRegistry payloadCodecs
    ) {
        this.stateSerializer = Objects.requireNonNull(stateSerializer, "stateSerializer");
        this.responseCodec = new PlayerBusinessResponseSnapshotCodec(payloadCodecs);
    }

    public byte[] encode(PlayerAgentMigrationPayload payload) {
        Objects.requireNonNull(payload, "payload");
        byte[] stateBytes = stateSerializer.serialize(payload.state());
        byte[] responseBytes = responseCodec.encode(payload.completedResponses());
        int size = CodedOutputStream.computeByteArraySize(1, stateBytes)
                + CodedOutputStream.computeByteArraySize(2, responseBytes);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeByteArray(1, stateBytes);
            output.writeByteArray(2, responseBytes);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player migration payload", e);
        }
    }

    public PlayerAgentMigrationPayload decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        byte[] stateBytes = new byte[0];
        byte[] responseBytes = new byte[0];
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> stateBytes = input.readByteArray();
                    case 2 -> responseBytes = input.readByteArray();
                    default -> input.skipField(tag);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player migration payload", e);
        }
        if (stateBytes.length == 0) {
            throw new IllegalArgumentException("player migration payload has no state");
        }
        PlayerStateSnapshot state = stateSerializer.deserialize(stateBytes);
        PlayerBusinessResponseSnapshot responses = responseBytes.length == 0
                ? PlayerBusinessResponseSnapshot.empty(state.playerId())
                : responseCodec.decode(responseBytes);
        return new PlayerAgentMigrationPayload(
                state,
                responses
        );
    }
}
