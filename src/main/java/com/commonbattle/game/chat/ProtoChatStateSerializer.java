package com.commonbattle.game.chat;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 基于 protobuf wire format 的 Chat 快照序列化器。
 * 未知字段会被跳过，新增字段可在灰度期间保持前后兼容。
 */
public final class ProtoChatStateSerializer implements ChatStateSerializer {
    @Override
    public byte[] serialize(VersionedChatState state) {
        byte[] snapshot = encodeSnapshot(state.snapshot());
        int size = CodedOutputStream.computeInt64Size(1, state.version())
                + CodedOutputStream.computeInt64Size(2, state.ownerToken())
                + CodedOutputStream.computeBytesSize(3, ByteString.copyFrom(snapshot));
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, state.version());
            output.writeInt64(2, state.ownerToken());
            output.writeByteArray(3, snapshot);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode chat state", e);
        }
    }

    @Override
    public VersionedChatState deserialize(byte[] bytes) {
        long version = 0;
        long ownerToken = 0;
        ChatChannelSnapshot snapshot = null;
        try {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> version = input.readInt64();
                    case 2 -> ownerToken = input.readInt64();
                    case 3 -> snapshot = decodeSnapshot(input.readByteArray());
                    default -> input.skipField(tag);
                }
            }
            if (snapshot == null) {
                throw new IllegalStateException("Chat state snapshot is missing");
            }
            return new VersionedChatState(version, ownerToken, snapshot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode chat state", e);
        }
    }

    private static byte[] encodeSnapshot(ChatChannelSnapshot snapshot) {
        List<byte[]> deliveries = snapshot.history().stream()
                .map(ProtoChatStateSerializer::encodeDelivery)
                .toList();
        List<byte[]> memberInterests = snapshot.memberAllianceIds().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> encodeMemberInterest(entry.getKey(), entry.getValue()))
                .toList();
        int size = CodedOutputStream.computeStringSize(1, snapshot.channelId())
                + snapshot.members().stream().mapToInt(member -> CodedOutputStream.computeInt64Size(2, member)).sum()
                + deliveries.stream().mapToInt(delivery ->
                CodedOutputStream.computeBytesSize(3, ByteString.copyFrom(delivery))).sum()
                + CodedOutputStream.computeInt64Size(4, snapshot.revision())
                + memberInterests.stream().mapToInt(interest ->
                CodedOutputStream.computeBytesSize(5, ByteString.copyFrom(interest))).sum();
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, snapshot.channelId());
            snapshot.members().stream().sorted().forEach(member -> writeMember(output, member));
            for (byte[] delivery : deliveries) {
                output.writeByteArray(3, delivery);
            }
            output.writeInt64(4, snapshot.revision());
            for (byte[] memberInterest : memberInterests) {
                output.writeByteArray(5, memberInterest);
            }
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode chat snapshot", e);
        }
    }

    private static ChatChannelSnapshot decodeSnapshot(byte[] bytes) throws IOException {
        String channelId = "";
        Set<Long> members = new HashSet<>();
        Map<Long, Long> memberAllianceIds = new HashMap<>();
        List<ChatDelivery> history = new ArrayList<>();
        long revision = 0;
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        int tag;
        while ((tag = input.readTag()) != 0) {
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1 -> channelId = input.readStringRequireUtf8();
                case 2 -> members.add(input.readInt64());
                case 3 -> history.add(decodeDelivery(input.readByteArray()));
                case 4 -> revision = input.readInt64();
                case 5 -> decodeMemberInterest(input.readByteArray(), memberAllianceIds);
                default -> input.skipField(tag);
            }
        }
        return new ChatChannelSnapshot(channelId, members, history, revision, memberAllianceIds);
    }

    private static byte[] encodeMemberInterest(long playerId, long allianceId) {
        int size = CodedOutputStream.computeInt64Size(1, playerId)
                + CodedOutputStream.computeInt64Size(2, allianceId);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, playerId);
            output.writeInt64(2, allianceId);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode chat member interest", e);
        }
    }

    private static void decodeMemberInterest(byte[] bytes, Map<Long, Long> memberAllianceIds) throws IOException {
        long playerId = 0;
        long allianceId = 0;
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        int tag;
        while ((tag = input.readTag()) != 0) {
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1 -> playerId = input.readInt64();
                case 2 -> allianceId = input.readInt64();
                default -> input.skipField(tag);
            }
        }
        if (playerId > 0) {
            memberAllianceIds.put(playerId, allianceId);
        }
    }

    private static byte[] encodeDelivery(ChatDelivery delivery) {
        int size = CodedOutputStream.computeStringSize(1, delivery.channelId())
                + CodedOutputStream.computeInt64Size(2, delivery.senderId())
                + CodedOutputStream.computeStringSize(3, delivery.senderName())
                + CodedOutputStream.computeStringSize(4, delivery.text())
                + CodedOutputStream.computeInt64Size(5, delivery.revision())
                + CodedOutputStream.computeInt64Size(6, delivery.sentAt().toEpochMilli());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, delivery.channelId());
            output.writeInt64(2, delivery.senderId());
            output.writeString(3, delivery.senderName());
            output.writeString(4, delivery.text());
            output.writeInt64(5, delivery.revision());
            output.writeInt64(6, delivery.sentAt().toEpochMilli());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode chat delivery", e);
        }
    }

    private static ChatDelivery decodeDelivery(byte[] bytes) throws IOException {
        String channelId = "";
        long senderId = 0;
        String senderName = "";
        String text = "";
        long revision = 0;
        long sentAt = 0;
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        int tag;
        while ((tag = input.readTag()) != 0) {
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1 -> channelId = input.readStringRequireUtf8();
                case 2 -> senderId = input.readInt64();
                case 3 -> senderName = input.readStringRequireUtf8();
                case 4 -> text = input.readStringRequireUtf8();
                case 5 -> revision = input.readInt64();
                case 6 -> sentAt = input.readInt64();
                default -> input.skipField(tag);
            }
        }
        return new ChatDelivery(channelId, senderId, senderName, text, revision, Instant.ofEpochMilli(sentAt));
    }

    private static void writeMember(CodedOutputStream output, long member) {
        try {
            output.writeInt64(2, member);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode chat member", e);
        }
    }
}
