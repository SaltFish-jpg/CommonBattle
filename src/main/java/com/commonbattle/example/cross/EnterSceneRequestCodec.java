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
                + CodedOutputStream.computeStringSize(2, payload.sceneId());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, payload.playerId());
            output.writeString(2, payload.sceneId());
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
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> playerId = input.readInt64();
                    case 2 -> sceneId = input.readString();
                    default -> input.skipField(tag);
                }
            }
            return new EnterSceneRequest(playerId, sceneId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode enter scene request", e);
        }
    }
}
