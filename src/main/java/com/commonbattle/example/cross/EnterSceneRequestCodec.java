package com.commonbattle.example.cross;

import com.commonbattle.cluster.protocol.PayloadCodec;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;

final class EnterSceneRequestCodec implements PayloadCodec<EnterSceneRequest> {
    @Override
    public String typeName() {
        return EnterSceneRequest.class.getName();
    }

    @Override
    public Class<EnterSceneRequest> javaType() {
        return EnterSceneRequest.class;
    }

    @Override
    public byte[] encode(EnterSceneRequest payload) {
        int size = CodedOutputStream.computeInt64Size(1, payload.playerId())
                + CodedOutputStream.computeStringSize(2, payload.sceneId())
                + CodedOutputStream.computeInt32Size(3, payload.chunkX())
                + CodedOutputStream.computeInt32Size(4, payload.chunkY());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, payload.playerId());
            output.writeString(2, payload.sceneId());
            output.writeInt32(3, payload.chunkX());
            output.writeInt32(4, payload.chunkY());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode enter scene request", e);
        }
    }

    @Override
    public EnterSceneRequest decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long playerId = 0;
        String sceneId = "";
        int chunkX = 0;
        int chunkY = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> playerId = input.readInt64();
                    case 2 -> sceneId = input.readString();
                    case 3 -> chunkX = input.readInt32();
                    case 4 -> chunkY = input.readInt32();
                    default -> input.skipField(tag);
                }
            }
            return new EnterSceneRequest(playerId, sceneId, chunkX, chunkY);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode enter scene request", e);
        }
    }
}
