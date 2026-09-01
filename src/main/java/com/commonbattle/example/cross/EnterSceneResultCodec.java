package com.commonbattle.example.cross;

import com.commonbattle.cluster.protocol.PayloadCodec;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;

final class EnterSceneResultCodec implements PayloadCodec<EnterSceneResult> {
    @Override
    public String typeName() {
        return EnterSceneResult.class.getName();
    }

    @Override
    public Class<EnterSceneResult> javaType() {
        return EnterSceneResult.class;
    }

    @Override
    public byte[] encode(EnterSceneResult payload) {
        int size = CodedOutputStream.computeInt64Size(1, payload.playerId())
                + CodedOutputStream.computeStringSize(2, payload.sceneId())
                + CodedOutputStream.computeInt64Size(3, payload.sceneActorId());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, payload.playerId());
            output.writeString(2, payload.sceneId());
            output.writeInt64(3, payload.sceneActorId());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode enter scene result", e);
        }
    }

    @Override
    public EnterSceneResult decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long playerId = 0;
        String sceneId = "";
        long sceneActorId = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> playerId = input.readInt64();
                    case 2 -> sceneId = input.readString();
                    case 3 -> sceneActorId = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return new EnterSceneResult(playerId, sceneId, sceneActorId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode enter scene result", e);
        }
    }
}
