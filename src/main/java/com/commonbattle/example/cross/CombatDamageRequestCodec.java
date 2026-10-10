package com.commonbattle.example.cross;

import com.commonbattle.cluster.protocol.PayloadCodec;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;

final class CombatDamageRequestCodec implements PayloadCodec<CombatDamageRequest> {
    @Override
    public String typeName() {
        return CombatDamageRequest.class.getName();
    }

    @Override
    public Class<CombatDamageRequest> javaType() {
        return CombatDamageRequest.class;
    }

    @Override
    public byte[] encode(CombatDamageRequest payload) {
        int size = CodedOutputStream.computeStringSize(1, payload.sceneId())
                + CodedOutputStream.computeInt64Size(2, payload.attackerId())
                + CodedOutputStream.computeInt64Size(3, payload.targetId())
                + CodedOutputStream.computeInt32Size(4, payload.damage());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, payload.sceneId());
            output.writeInt64(2, payload.attackerId());
            output.writeInt64(3, payload.targetId());
            output.writeInt32(4, payload.damage());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode combat damage request", e);
        }
    }

    @Override
    public CombatDamageRequest decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String sceneId = "";
        long attackerId = 0;
        long targetId = 0;
        int damage = 1;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> sceneId = input.readString();
                    case 2 -> attackerId = input.readInt64();
                    case 3 -> targetId = input.readInt64();
                    case 4 -> damage = input.readInt32();
                    default -> input.skipField(tag);
                }
            }
            return new CombatDamageRequest(sceneId, attackerId, targetId, damage);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode combat damage request", e);
        }
    }
}
