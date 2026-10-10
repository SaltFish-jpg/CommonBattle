package com.commonbattle.example.cross;

import com.commonbattle.cluster.protocol.PayloadCodec;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;

final class CombatDamageResultCodec implements PayloadCodec<CombatDamageResult> {
    @Override
    public String typeName() {
        return CombatDamageResult.class.getName();
    }

    @Override
    public Class<CombatDamageResult> javaType() {
        return CombatDamageResult.class;
    }

    @Override
    public byte[] encode(CombatDamageResult payload) {
        int size = CodedOutputStream.computeStringSize(1, payload.sceneId())
                + CodedOutputStream.computeInt64Size(2, payload.attackerId())
                + CodedOutputStream.computeInt64Size(3, payload.targetId())
                + CodedOutputStream.computeInt32Size(4, payload.shardIndex())
                + CodedOutputStream.computeInt32Size(5, payload.damage())
                + CodedOutputStream.computeInt32Size(6, payload.hpBefore())
                + CodedOutputStream.computeInt32Size(7, payload.hpAfter())
                + CodedOutputStream.computeBoolSize(8, payload.applied());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, payload.sceneId());
            output.writeInt64(2, payload.attackerId());
            output.writeInt64(3, payload.targetId());
            output.writeInt32(4, payload.shardIndex());
            output.writeInt32(5, payload.damage());
            output.writeInt32(6, payload.hpBefore());
            output.writeInt32(7, payload.hpAfter());
            output.writeBool(8, payload.applied());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode combat damage result", e);
        }
    }

    @Override
    public CombatDamageResult decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String sceneId = "";
        long attackerId = 0;
        long targetId = 0;
        int shardIndex = -1;
        int damage = 0;
        int hpBefore = 0;
        int hpAfter = 0;
        boolean applied = false;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> sceneId = input.readString();
                    case 2 -> attackerId = input.readInt64();
                    case 3 -> targetId = input.readInt64();
                    case 4 -> shardIndex = input.readInt32();
                    case 5 -> damage = input.readInt32();
                    case 6 -> hpBefore = input.readInt32();
                    case 7 -> hpAfter = input.readInt32();
                    case 8 -> applied = input.readBool();
                    default -> input.skipField(tag);
                }
            }
            return new CombatDamageResult(sceneId, attackerId, targetId, shardIndex, damage, hpBefore, hpAfter, applied);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode combat damage result", e);
        }
    }
}
