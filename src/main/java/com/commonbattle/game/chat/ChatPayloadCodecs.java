package com.commonbattle.game.chat;

import com.commonbattle.cluster.protocol.PayloadCodec;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Chat 服务 RPC payload codec。
 * 字段号只追加不复用，便于灰度、回滚和不同服务版本短期共存。
 */
public final class ChatPayloadCodecs {
    private ChatPayloadCodecs() {
    }

    public static PayloadCodecRegistry registerTo(PayloadCodecRegistry registry) {
        registry.register(new JoinRequestCodec());
        registry.register(new LeaveRequestCodec());
        registry.register(new SendRequestCodec());
        registry.register(new JoinResultCodec());
        registry.register(new LeaveResultCodec());
        registry.register(new SendResultCodec());
        registry.register(new DeliveryCodec());
        registry.register(new WorldJoinRequestCodec());
        registry.register(new WorldLeaveRequestCodec());
        registry.register(new WorldSendRequestCodec());
        registry.register(new AllianceJoinRequestCodec());
        registry.register(new AllianceLeaveRequestCodec());
        registry.register(new AllianceSendRequestCodec());
        registry.register(new DirectSendRequestCodec());
        return registry;
    }

    private static final class JoinRequestCodec implements PayloadCodec<ChatJoinRequest> {
        @Override
        public String typeName() {
            return ChatJoinRequest.class.getName();
        }

        @Override
        public Class<ChatJoinRequest> javaType() {
            return ChatJoinRequest.class;
        }

        @Override
        public byte[] encode(ChatJoinRequest payload) {
            int size = CodedOutputStream.computeStringSize(1, payload.channelId())
                    + CodedOutputStream.computeInt64Size(2, payload.playerId())
                    + CodedOutputStream.computeInt64Size(3, payload.allianceId());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeString(1, payload.channelId());
                output.writeInt64(2, payload.playerId());
                output.writeInt64(3, payload.allianceId());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode chat join request", e);
            }
        }

        @Override
        public ChatJoinRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            String channelId = "";
            long playerId = 0;
            long allianceId = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> channelId = input.readString();
                        case 2 -> playerId = input.readInt64();
                        case 3 -> allianceId = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                return new ChatJoinRequest(channelId, playerId, allianceId);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode chat join request", e);
            }
        }
    }

    private static final class LeaveRequestCodec implements PayloadCodec<ChatLeaveRequest> {
        @Override
        public String typeName() {
            return ChatLeaveRequest.class.getName();
        }

        @Override
        public Class<ChatLeaveRequest> javaType() {
            return ChatLeaveRequest.class;
        }

        @Override
        public byte[] encode(ChatLeaveRequest payload) {
            int size = CodedOutputStream.computeStringSize(1, payload.channelId())
                    + CodedOutputStream.computeInt64Size(2, payload.playerId());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeString(1, payload.channelId());
                output.writeInt64(2, payload.playerId());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode chat leave request", e);
            }
        }

        @Override
        public ChatLeaveRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            String channelId = "";
            long playerId = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> channelId = input.readString();
                        case 2 -> playerId = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                return new ChatLeaveRequest(channelId, playerId);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode chat leave request", e);
            }
        }
    }

    private static final class SendRequestCodec implements PayloadCodec<ChatSendRequest> {
        @Override
        public String typeName() {
            return ChatSendRequest.class.getName();
        }

        @Override
        public Class<ChatSendRequest> javaType() {
            return ChatSendRequest.class;
        }

        @Override
        public byte[] encode(ChatSendRequest payload) {
            int size = CodedOutputStream.computeStringSize(1, payload.channelId())
                    + CodedOutputStream.computeInt64Size(2, payload.senderId())
                    + CodedOutputStream.computeStringSize(3, payload.text())
                    + CodedOutputStream.computeInt64Size(4, payload.requiredProfileRevision());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeString(1, payload.channelId());
                output.writeInt64(2, payload.senderId());
                output.writeString(3, payload.text());
                output.writeInt64(4, payload.requiredProfileRevision());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode chat send request", e);
            }
        }

        @Override
        public ChatSendRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            String channelId = "";
            long senderId = 0;
            String text = "";
            long requiredProfileRevision = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> channelId = input.readString();
                        case 2 -> senderId = input.readInt64();
                        case 3 -> text = input.readString();
                        case 4 -> requiredProfileRevision = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                return new ChatSendRequest(channelId, senderId, text, requiredProfileRevision);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode chat send request", e);
            }
        }
    }

    private static final class JoinResultCodec implements PayloadCodec<ChatJoinResult> {
        @Override
        public String typeName() {
            return ChatJoinResult.class.getName();
        }

        @Override
        public Class<ChatJoinResult> javaType() {
            return ChatJoinResult.class;
        }

        @Override
        public byte[] encode(ChatJoinResult payload) {
            return encodeMemberResult(payload.status().name(), payload.channelId(), payload.playerId(), payload.members());
        }

        @Override
        public ChatJoinResult decode(byte[] bytes) {
            MemberResult result = decodeMemberResult(bytes);
            return new ChatJoinResult(ChatJoinStatus.valueOf(result.status()), result.channelId(), result.playerId(), result.members());
        }
    }

    private static final class LeaveResultCodec implements PayloadCodec<ChatLeaveResult> {
        @Override
        public String typeName() {
            return ChatLeaveResult.class.getName();
        }

        @Override
        public Class<ChatLeaveResult> javaType() {
            return ChatLeaveResult.class;
        }

        @Override
        public byte[] encode(ChatLeaveResult payload) {
            return encodeMemberResult(payload.status().name(), payload.channelId(), payload.playerId(), payload.members());
        }

        @Override
        public ChatLeaveResult decode(byte[] bytes) {
            MemberResult result = decodeMemberResult(bytes);
            return new ChatLeaveResult(ChatLeaveStatus.valueOf(result.status()), result.channelId(), result.playerId(), result.members());
        }
    }

    private static final class SendResultCodec implements PayloadCodec<ChatSendResult> {
        @Override
        public String typeName() {
            return ChatSendResult.class.getName();
        }

        @Override
        public Class<ChatSendResult> javaType() {
            return ChatSendResult.class;
        }

        @Override
        public byte[] encode(ChatSendResult payload) {
            Optional<ChatDelivery> delivery = payload.delivery();
            byte[] deliveryBytes = delivery.map(ChatPayloadCodecs::encodeDelivery).orElseGet(() -> new byte[0]);
            int size = CodedOutputStream.computeStringSize(1, payload.status().name())
                    + CodedOutputStream.computeBoolSize(2, delivery.isPresent());
            if (delivery.isPresent()) {
                size += CodedOutputStream.computeBytesSize(3, com.google.protobuf.ByteString.copyFrom(deliveryBytes));
            }
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeString(1, payload.status().name());
                output.writeBool(2, delivery.isPresent());
                if (delivery.isPresent()) {
                    output.writeBytes(3, com.google.protobuf.ByteString.copyFrom(deliveryBytes));
                }
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode chat send result", e);
            }
        }

        @Override
        public ChatSendResult decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            ChatSendStatus status = ChatSendStatus.EMPTY_TEXT;
            boolean hasDelivery = false;
            byte[] deliveryBytes = new byte[0];
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> status = ChatSendStatus.valueOf(input.readString());
                        case 2 -> hasDelivery = input.readBool();
                        case 3 -> deliveryBytes = input.readBytes().toByteArray();
                        default -> input.skipField(tag);
                    }
                }
                if (hasDelivery) {
                    return ChatSendResult.sent(decodeDelivery(deliveryBytes));
                }
                return ChatSendResult.rejected(status);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode chat send result", e);
            }
        }
    }

    private static final class DeliveryCodec implements PayloadCodec<ChatDelivery> {
        @Override
        public String typeName() {
            return ChatDelivery.class.getName();
        }

        @Override
        public Class<ChatDelivery> javaType() {
            return ChatDelivery.class;
        }

        @Override
        public byte[] encode(ChatDelivery payload) {
            return encodeDelivery(payload);
        }

        @Override
        public ChatDelivery decode(byte[] bytes) {
            return decodeDelivery(bytes);
        }
    }

    private static final class WorldJoinRequestCodec implements PayloadCodec<WorldChatJoinRequest> {
        @Override
        public String typeName() {
            return WorldChatJoinRequest.class.getName();
        }

        @Override
        public Class<WorldChatJoinRequest> javaType() {
            return WorldChatJoinRequest.class;
        }

        @Override
        public byte[] encode(WorldChatJoinRequest payload) {
            return encodeStringLongLong(payload.worldId(), payload.playerId(), payload.allianceId());
        }

        @Override
        public WorldChatJoinRequest decode(byte[] bytes) {
            StringLongLong decoded = decodeStringLongLong(bytes);
            return new WorldChatJoinRequest(decoded.text(), decoded.first(), decoded.second());
        }
    }

    private static final class WorldLeaveRequestCodec implements PayloadCodec<WorldChatLeaveRequest> {
        @Override
        public String typeName() {
            return WorldChatLeaveRequest.class.getName();
        }

        @Override
        public Class<WorldChatLeaveRequest> javaType() {
            return WorldChatLeaveRequest.class;
        }

        @Override
        public byte[] encode(WorldChatLeaveRequest payload) {
            return encodeStringLong(payload.worldId(), payload.playerId());
        }

        @Override
        public WorldChatLeaveRequest decode(byte[] bytes) {
            StringLong decoded = decodeStringLong(bytes);
            return new WorldChatLeaveRequest(decoded.text(), decoded.value());
        }
    }

    private static final class WorldSendRequestCodec implements PayloadCodec<WorldChatSendRequest> {
        @Override
        public String typeName() {
            return WorldChatSendRequest.class.getName();
        }

        @Override
        public Class<WorldChatSendRequest> javaType() {
            return WorldChatSendRequest.class;
        }

        @Override
        public byte[] encode(WorldChatSendRequest payload) {
            return encodeStringLongStringLong(
                    payload.worldId(),
                    payload.senderId(),
                    payload.text(),
                    payload.requiredProfileRevision()
            );
        }

        @Override
        public WorldChatSendRequest decode(byte[] bytes) {
            StringLongStringLong decoded = decodeStringLongStringLong(bytes);
            return new WorldChatSendRequest(decoded.firstText(), decoded.firstLong(), decoded.secondText(), decoded.secondLong());
        }
    }

    private static final class AllianceJoinRequestCodec implements PayloadCodec<AllianceChatJoinRequest> {
        @Override
        public String typeName() {
            return AllianceChatJoinRequest.class.getName();
        }

        @Override
        public Class<AllianceChatJoinRequest> javaType() {
            return AllianceChatJoinRequest.class;
        }

        @Override
        public byte[] encode(AllianceChatJoinRequest payload) {
            return encodeLongLong(payload.allianceId(), payload.playerId());
        }

        @Override
        public AllianceChatJoinRequest decode(byte[] bytes) {
            LongLong decoded = decodeLongLong(bytes);
            return new AllianceChatJoinRequest(decoded.first(), decoded.second());
        }
    }

    private static final class AllianceLeaveRequestCodec implements PayloadCodec<AllianceChatLeaveRequest> {
        @Override
        public String typeName() {
            return AllianceChatLeaveRequest.class.getName();
        }

        @Override
        public Class<AllianceChatLeaveRequest> javaType() {
            return AllianceChatLeaveRequest.class;
        }

        @Override
        public byte[] encode(AllianceChatLeaveRequest payload) {
            return encodeLongLong(payload.allianceId(), payload.playerId());
        }

        @Override
        public AllianceChatLeaveRequest decode(byte[] bytes) {
            LongLong decoded = decodeLongLong(bytes);
            return new AllianceChatLeaveRequest(decoded.first(), decoded.second());
        }
    }

    private static final class AllianceSendRequestCodec implements PayloadCodec<AllianceChatSendRequest> {
        @Override
        public String typeName() {
            return AllianceChatSendRequest.class.getName();
        }

        @Override
        public Class<AllianceChatSendRequest> javaType() {
            return AllianceChatSendRequest.class;
        }

        @Override
        public byte[] encode(AllianceChatSendRequest payload) {
            return encodeLongLongStringLong(
                    payload.allianceId(),
                    payload.senderId(),
                    payload.text(),
                    payload.requiredProfileRevision()
            );
        }

        @Override
        public AllianceChatSendRequest decode(byte[] bytes) {
            LongLongStringLong decoded = decodeLongLongStringLong(bytes);
            return new AllianceChatSendRequest(decoded.first(), decoded.second(), decoded.text(), decoded.revision());
        }
    }

    private static final class DirectSendRequestCodec implements PayloadCodec<DirectChatSendRequest> {
        @Override
        public String typeName() {
            return DirectChatSendRequest.class.getName();
        }

        @Override
        public Class<DirectChatSendRequest> javaType() {
            return DirectChatSendRequest.class;
        }

        @Override
        public byte[] encode(DirectChatSendRequest payload) {
            return encodeLongLongStringLong(
                    payload.senderId(),
                    payload.receiverId(),
                    payload.text(),
                    payload.requiredProfileRevision()
            );
        }

        @Override
        public DirectChatSendRequest decode(byte[] bytes) {
            LongLongStringLong decoded = decodeLongLongStringLong(bytes);
            return new DirectChatSendRequest(decoded.first(), decoded.second(), decoded.text(), decoded.revision());
        }
    }

    private static byte[] encodeMemberResult(String status, String channelId, long playerId, int members) {
        int size = CodedOutputStream.computeStringSize(1, status)
                + CodedOutputStream.computeStringSize(2, channelId)
                + CodedOutputStream.computeInt64Size(3, playerId)
                + CodedOutputStream.computeInt32Size(4, members);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, status);
            output.writeString(2, channelId);
            output.writeInt64(3, playerId);
            output.writeInt32(4, members);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode chat member result", e);
        }
    }

    private static MemberResult decodeMemberResult(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String status = "";
        String channelId = "";
        long playerId = 0;
        int members = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> status = input.readString();
                    case 2 -> channelId = input.readString();
                    case 3 -> playerId = input.readInt64();
                    case 4 -> members = input.readInt32();
                    default -> input.skipField(tag);
                }
            }
            return new MemberResult(status, channelId, playerId, members);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode chat member result", e);
        }
    }

    private static byte[] encodeDelivery(ChatDelivery payload) {
        int size = CodedOutputStream.computeStringSize(1, payload.channelId())
                + CodedOutputStream.computeInt64Size(2, payload.senderId())
                + CodedOutputStream.computeStringSize(3, payload.senderName())
                + CodedOutputStream.computeStringSize(4, payload.text())
                + CodedOutputStream.computeInt64Size(5, payload.revision())
                + CodedOutputStream.computeInt64Size(6, payload.sentAt().toEpochMilli());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, payload.channelId());
            output.writeInt64(2, payload.senderId());
            output.writeString(3, payload.senderName());
            output.writeString(4, payload.text());
            output.writeInt64(5, payload.revision());
            output.writeInt64(6, payload.sentAt().toEpochMilli());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode chat delivery", e);
        }
    }

    private static ChatDelivery decodeDelivery(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String channelId = "";
        long senderId = 0;
        String senderName = "";
        String text = "";
        long revision = 0;
        long sentAtMillis = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> channelId = input.readString();
                    case 2 -> senderId = input.readInt64();
                    case 3 -> senderName = input.readString();
                    case 4 -> text = input.readString();
                    case 5 -> revision = input.readInt64();
                    case 6 -> sentAtMillis = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return new ChatDelivery(channelId, senderId, senderName, text, revision, Instant.ofEpochMilli(sentAtMillis));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode chat delivery", e);
        }
    }

    private static byte[] encodeStringLong(String text, long value) {
        int size = CodedOutputStream.computeStringSize(1, text)
                + CodedOutputStream.computeInt64Size(2, value);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, text);
            output.writeInt64(2, value);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode chat payload", e);
        }
    }

    private static StringLong decodeStringLong(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String text = "";
        long value = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> text = input.readString();
                    case 2 -> value = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return new StringLong(text, value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode chat payload", e);
        }
    }

    private static byte[] encodeStringLongLong(String text, long first, long second) {
        int size = CodedOutputStream.computeStringSize(1, text)
                + CodedOutputStream.computeInt64Size(2, first)
                + CodedOutputStream.computeInt64Size(3, second);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, text);
            output.writeInt64(2, first);
            output.writeInt64(3, second);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode chat payload", e);
        }
    }

    private static StringLongLong decodeStringLongLong(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String text = "";
        long first = 0;
        long second = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> text = input.readString();
                    case 2 -> first = input.readInt64();
                    case 3 -> second = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return new StringLongLong(text, first, second);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode chat payload", e);
        }
    }

    private static byte[] encodeLongLong(long first, long second) {
        int size = CodedOutputStream.computeInt64Size(1, first)
                + CodedOutputStream.computeInt64Size(2, second);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, first);
            output.writeInt64(2, second);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode chat payload", e);
        }
    }

    private static LongLong decodeLongLong(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long first = 0;
        long second = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> first = input.readInt64();
                    case 2 -> second = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return new LongLong(first, second);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode chat payload", e);
        }
    }

    private static byte[] encodeStringLongStringLong(String firstText, long firstLong, String secondText, long secondLong) {
        int size = CodedOutputStream.computeStringSize(1, firstText)
                + CodedOutputStream.computeInt64Size(2, firstLong)
                + CodedOutputStream.computeStringSize(3, secondText)
                + CodedOutputStream.computeInt64Size(4, secondLong);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, firstText);
            output.writeInt64(2, firstLong);
            output.writeString(3, secondText);
            output.writeInt64(4, secondLong);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode chat payload", e);
        }
    }

    private static StringLongStringLong decodeStringLongStringLong(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String firstText = "";
        long firstLong = 0;
        String secondText = "";
        long secondLong = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> firstText = input.readString();
                    case 2 -> firstLong = input.readInt64();
                    case 3 -> secondText = input.readString();
                    case 4 -> secondLong = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return new StringLongStringLong(firstText, firstLong, secondText, secondLong);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode chat payload", e);
        }
    }

    private static byte[] encodeLongLongStringLong(long first, long second, String text, long revision) {
        int size = CodedOutputStream.computeInt64Size(1, first)
                + CodedOutputStream.computeInt64Size(2, second)
                + CodedOutputStream.computeStringSize(3, text)
                + CodedOutputStream.computeInt64Size(4, revision);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, first);
            output.writeInt64(2, second);
            output.writeString(3, text);
            output.writeInt64(4, revision);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode chat payload", e);
        }
    }

    private static LongLongStringLong decodeLongLongStringLong(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long first = 0;
        long second = 0;
        String text = "";
        long revision = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> first = input.readInt64();
                    case 2 -> second = input.readInt64();
                    case 3 -> text = input.readString();
                    case 4 -> revision = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return new LongLongStringLong(first, second, text, revision);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode chat payload", e);
        }
    }

    private record MemberResult(String status, String channelId, long playerId, int members) {
    }

    private record StringLong(String text, long value) {
    }

    private record StringLongLong(String text, long first, long second) {
    }

    private record LongLong(long first, long second) {
    }

    private record StringLongStringLong(String firstText, long firstLong, String secondText, long secondLong) {
    }

    private record LongLongStringLong(long first, long second, String text, long revision) {
    }
}
