package com.commonbattle.example.cross;

import com.commonbattle.cluster.protocol.PayloadCodec;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;

final class LeaveSceneResultCodec implements PayloadCodec<LeaveSceneResult> {
    @Override
    public String typeName() {
        return LeaveSceneResult.class.getName();
    }

    @Override
    public Class<LeaveSceneResult> javaType() {
        return LeaveSceneResult.class;
    }

    @Override
    public byte[] encode(LeaveSceneResult payload) {
        int size = CodedOutputStream.computeInt64Size(1, payload.playerId())
                + CodedOutputStream.computeStringSize(2, payload.sceneId())
                + CodedOutputStream.computeBoolSize(3, payload.left());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, payload.playerId());
            output.writeString(2, payload.sceneId());
            output.writeBool(3, payload.left());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode leave scene result", e);
        }
    }

    @Override
    public LeaveSceneResult decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long playerId = 0;
        String sceneId = "";
        boolean left = false;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> playerId = input.readInt64();
                    case 2 -> sceneId = input.readString();
                    case 3 -> left = input.readBool();
                    default -> input.skipField(tag);
                }
            }
            return new LeaveSceneResult(playerId, sceneId, left);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode leave scene result", e);
        }
    }
}
