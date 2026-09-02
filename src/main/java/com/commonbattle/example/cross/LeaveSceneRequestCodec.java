package com.commonbattle.example.cross;

import com.commonbattle.cluster.protocol.PayloadCodec;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;

final class LeaveSceneRequestCodec implements PayloadCodec<LeaveSceneRequest> {
    @Override
    public String typeName() {
        return LeaveSceneRequest.class.getName();
    }

    @Override
    public Class<LeaveSceneRequest> javaType() {
        return LeaveSceneRequest.class;
    }

    @Override
    public byte[] encode(LeaveSceneRequest payload) {
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
            throw new IllegalStateException("Failed to encode leave scene request", e);
        }
    }

    @Override
    public LeaveSceneRequest decode(byte[] bytes) {
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
            return new LeaveSceneRequest(playerId, sceneId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode leave scene request", e);
        }
    }
}
