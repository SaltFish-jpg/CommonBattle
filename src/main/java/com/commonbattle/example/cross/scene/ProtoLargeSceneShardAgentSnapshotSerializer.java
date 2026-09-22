package com.commonbattle.example.cross.scene;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * 基于 protobuf wire format 的大场景 shard 迁移快照序列化器。
 * 字段号只允许尾部追加，保证迁移快照跨灰度版本可解码。
 */
public final class ProtoLargeSceneShardAgentSnapshotSerializer implements LargeSceneShardAgentSnapshotSerializer {
    @Override
    public byte[] serialize(LargeSceneShardAgentSnapshot snapshot) {
        int size = CodedOutputStream.computeStringSize(1, snapshot.sceneId())
                + CodedOutputStream.computeInt32Size(2, snapshot.shardIndex())
                + CodedOutputStream.computeInt32Size(3, snapshot.shardCount());
        for (long playerId : snapshot.players()) {
            size += CodedOutputStream.computeInt64Size(4, playerId);
        }
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, snapshot.sceneId());
            output.writeInt32(2, snapshot.shardIndex());
            output.writeInt32(3, snapshot.shardCount());
            for (long playerId : snapshot.players().stream().sorted().toList()) {
                output.writeInt64(4, playerId);
            }
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode large scene shard snapshot", e);
        }
    }

    @Override
    public LargeSceneShardAgentSnapshot deserialize(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String sceneId = "";
        int shardIndex = 0;
        int shardCount = 1;
        Set<Long> players = new HashSet<>();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> sceneId = input.readString();
                    case 2 -> shardIndex = input.readInt32();
                    case 3 -> shardCount = input.readInt32();
                    case 4 -> players.add(input.readInt64());
                    default -> input.skipField(tag);
                }
            }
            return new LargeSceneShardAgentSnapshot(sceneId, shardIndex, shardCount, players);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode large scene shard snapshot", e);
        }
    }
}
