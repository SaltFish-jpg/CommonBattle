package com.commonbattle.example.cross;

import com.commonbattle.cluster.protocol.PayloadCodec;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class MoveSceneResultCodec implements PayloadCodec<MoveSceneResult> {
    @Override
    public String typeName() {
        return MoveSceneResult.class.getName();
    }

    @Override
    public Class<MoveSceneResult> javaType() {
        return MoveSceneResult.class;
    }

    @Override
    public byte[] encode(MoveSceneResult payload) {
        int size = CodedOutputStream.computeInt64Size(1, payload.playerId())
                + CodedOutputStream.computeStringSize(2, payload.sceneId())
                + CodedOutputStream.computeInt32Size(3, payload.chunkX())
                + CodedOutputStream.computeInt32Size(4, payload.chunkY())
                + CodedOutputStream.computeInt32Size(5, payload.previousShardIndex())
                + CodedOutputStream.computeInt32Size(6, payload.shardIndex())
                + CodedOutputStream.computeBoolSize(7, payload.moved());
        size += int64ListSize(8, payload.visibleEnter());
        size += int64ListSize(9, payload.visibleLeave());
        size += int64ListSize(10, payload.observerEnter());
        size += int64ListSize(11, payload.observerLeave());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, payload.playerId());
            output.writeString(2, payload.sceneId());
            output.writeInt32(3, payload.chunkX());
            output.writeInt32(4, payload.chunkY());
            output.writeInt32(5, payload.previousShardIndex());
            output.writeInt32(6, payload.shardIndex());
            output.writeBool(7, payload.moved());
            writeInt64List(output, 8, payload.visibleEnter());
            writeInt64List(output, 9, payload.visibleLeave());
            writeInt64List(output, 10, payload.observerEnter());
            writeInt64List(output, 11, payload.observerLeave());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode move scene result", e);
        }
    }

    @Override
    public MoveSceneResult decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long playerId = 0;
        String sceneId = "";
        int chunkX = 0;
        int chunkY = 0;
        int previousShardIndex = -1;
        int shardIndex = -1;
        boolean moved = false;
        List<Long> visibleEnter = new ArrayList<>();
        List<Long> visibleLeave = new ArrayList<>();
        List<Long> observerEnter = new ArrayList<>();
        List<Long> observerLeave = new ArrayList<>();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> playerId = input.readInt64();
                    case 2 -> sceneId = input.readString();
                    case 3 -> chunkX = input.readInt32();
                    case 4 -> chunkY = input.readInt32();
                    case 5 -> previousShardIndex = input.readInt32();
                    case 6 -> shardIndex = input.readInt32();
                    case 7 -> moved = input.readBool();
                    case 8 -> visibleEnter.add(input.readInt64());
                    case 9 -> visibleLeave.add(input.readInt64());
                    case 10 -> observerEnter.add(input.readInt64());
                    case 11 -> observerLeave.add(input.readInt64());
                    default -> input.skipField(tag);
                }
            }
            return new MoveSceneResult(playerId, sceneId, chunkX, chunkY, previousShardIndex, shardIndex, moved,
                    visibleEnter, visibleLeave, observerEnter, observerLeave);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode move scene result", e);
        }
    }

    private static int int64ListSize(int fieldNumber, List<Long> values) {
        int size = 0;
        for (Long value : values) {
            size += CodedOutputStream.computeInt64Size(fieldNumber, value);
        }
        return size;
    }

    private static void writeInt64List(CodedOutputStream output, int fieldNumber, List<Long> values) throws IOException {
        for (Long value : values) {
            output.writeInt64(fieldNumber, value);
        }
    }
}
