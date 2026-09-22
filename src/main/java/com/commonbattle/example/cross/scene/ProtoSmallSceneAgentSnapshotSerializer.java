package com.commonbattle.example.cross.scene;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * 基于 protobuf wire format 的小场景迁移快照序列化器。
 * 字段号只允许尾部追加，保证 Scene 迁移快照可以跨灰度版本解码。
 */
public final class ProtoSmallSceneAgentSnapshotSerializer implements SmallSceneAgentSnapshotSerializer {
    @Override
    public byte[] serialize(SmallSceneAgentSnapshot snapshot) {
        int size = CodedOutputStream.computeStringSize(1, snapshot.sceneId());
        for (long playerId : snapshot.players()) {
            size += CodedOutputStream.computeInt64Size(2, playerId);
        }
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, snapshot.sceneId());
            for (long playerId : snapshot.players().stream().sorted().toList()) {
                output.writeInt64(2, playerId);
            }
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode small scene snapshot", e);
        }
    }

    @Override
    public SmallSceneAgentSnapshot deserialize(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String sceneId = "";
        Set<Long> players = new HashSet<>();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> sceneId = input.readString();
                    case 2 -> players.add(input.readInt64());
                    default -> input.skipField(tag);
                }
            }
            return new SmallSceneAgentSnapshot(sceneId, players);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode small scene snapshot", e);
        }
    }
}
