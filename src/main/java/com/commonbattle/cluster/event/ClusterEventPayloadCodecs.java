package com.commonbattle.cluster.event;

import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.protocol.PayloadCodec;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import com.commonbattle.game.social.AllianceMemberAction;
import com.commonbattle.game.social.AllianceMemberChangedEvent;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 跨服事件通道 payload 注册入口。
 * 事件请求使用显式 wire codec，避免 Java record、接口字段和运行时 Bean 序列化影响灰度兼容。
 */
public final class ClusterEventPayloadCodecs {
    private static final Map<Class<?>, EventCodec<?>> EVENT_BY_CLASS = Map.of(
            ProfileChangedEvent.class, new ProfileChangedEventCodec(),
            AllianceMemberChangedEvent.class, new AllianceMemberChangedEventCodec()
    );
    private static final Map<String, EventCodec<?>> EVENT_BY_TYPE = indexByType();

    private ClusterEventPayloadCodecs() {
    }

    public static PayloadCodecRegistry registerTo(PayloadCodecRegistry registry) {
        registry.register(new EventSubscribeRequestCodec());
        registry.register(new EventUnsubscribeRequestCodec());
        registry.register(new EventPublishRequestCodec());
        registry.register(new EventDeliverRequestCodec());
        return registry;
    }

    private static Map<String, EventCodec<?>> indexByType() {
        Map<String, EventCodec<?>> result = new HashMap<>();
        EVENT_BY_CLASS.values().forEach(codec -> result.put(codec.typeName(), codec));
        return Map.copyOf(result);
    }

    private static EventCodec<?> eventCodec(VersionedEvent event) {
        EventCodec<?> codec = EVENT_BY_CLASS.get(event.getClass());
        if (codec == null) {
            throw new IllegalArgumentException("No cluster event codec for " + event.getClass().getName());
        }
        return codec;
    }

    private static EventCodec<?> eventCodec(String typeName) {
        EventCodec<?> codec = EVENT_BY_TYPE.get(typeName);
        if (codec == null) {
            throw new IllegalArgumentException("No cluster event codec for " + typeName);
        }
        return codec;
    }

    @SuppressWarnings("unchecked")
    private static <T extends VersionedEvent> byte[] encodeEvent(EventCodec<?> codec, VersionedEvent event) {
        return ((EventCodec<T>) codec).encode((T) event);
    }

    private interface EventCodec<T extends VersionedEvent> {
        String typeName();

        byte[] encode(T event);

        T decode(byte[] bytes);
    }

    private abstract static class EventRequestCodec<T> implements PayloadCodec<T> {
        byte[] encodeEventRequest(VersionedEvent event) {
            EventCodec<?> codec = eventCodec(event);
            byte[] eventBytes = encodeEvent(codec, event);
            int size = stringSize(1, codec.typeName())
                    + CodedOutputStream.computeByteArraySize(2, eventBytes);
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeString(1, codec.typeName());
                output.writeByteArray(2, eventBytes);
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode event request", e);
            }
        }

        VersionedEvent decodeEventRequest(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            String eventType = "";
            byte[] eventBytes = new byte[0];
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> eventType = input.readString();
                        case 2 -> eventBytes = input.readByteArray();
                        default -> input.skipField(tag);
                    }
                }
                return eventCodec(eventType).decode(eventBytes);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode event request", e);
            }
        }
    }

    private static final class EventSubscribeRequestCodec implements PayloadCodec<EventSubscribeRequest> {
        @Override
        public String typeName() {
            return EventSubscribeRequest.class.getName();
        }

        @Override
        public Class<EventSubscribeRequest> javaType() {
            return EventSubscribeRequest.class;
        }

        @Override
        public byte[] encode(EventSubscribeRequest payload) {
            return encodeSubscription(payload.subscriber(), payload.topic());
        }

        @Override
        public EventSubscribeRequest decode(byte[] bytes) {
            Subscription subscription = decodeSubscription(bytes);
            return new EventSubscribeRequest(subscription.subscriber(), subscription.topic());
        }
    }

    private static final class EventUnsubscribeRequestCodec implements PayloadCodec<EventUnsubscribeRequest> {
        @Override
        public String typeName() {
            return EventUnsubscribeRequest.class.getName();
        }

        @Override
        public Class<EventUnsubscribeRequest> javaType() {
            return EventUnsubscribeRequest.class;
        }

        @Override
        public byte[] encode(EventUnsubscribeRequest payload) {
            return encodeSubscription(payload.subscriber(), payload.topic());
        }

        @Override
        public EventUnsubscribeRequest decode(byte[] bytes) {
            Subscription subscription = decodeSubscription(bytes);
            return new EventUnsubscribeRequest(subscription.subscriber(), subscription.topic());
        }
    }

    private static final class EventPublishRequestCodec extends EventRequestCodec<EventPublishRequest> {
        @Override
        public String typeName() {
            return EventPublishRequest.class.getName();
        }

        @Override
        public Class<EventPublishRequest> javaType() {
            return EventPublishRequest.class;
        }

        @Override
        public byte[] encode(EventPublishRequest payload) {
            return encodeEventRequest(payload.event());
        }

        @Override
        public EventPublishRequest decode(byte[] bytes) {
            return new EventPublishRequest(decodeEventRequest(bytes));
        }
    }

    private static final class EventDeliverRequestCodec extends EventRequestCodec<EventDeliverRequest> {
        @Override
        public String typeName() {
            return EventDeliverRequest.class.getName();
        }

        @Override
        public Class<EventDeliverRequest> javaType() {
            return EventDeliverRequest.class;
        }

        @Override
        public byte[] encode(EventDeliverRequest payload) {
            return encodeEventRequest(payload.event());
        }

        @Override
        public EventDeliverRequest decode(byte[] bytes) {
            return new EventDeliverRequest(decodeEventRequest(bytes));
        }
    }

    private static final class ProfileChangedEventCodec implements EventCodec<ProfileChangedEvent> {
        @Override
        public String typeName() {
            return ProfileChangedEvent.class.getName();
        }

        @Override
        public byte[] encode(ProfileChangedEvent event) {
            byte[] snapshot = encodeSnapshot(event.snapshot());
            int size = CodedOutputStream.computeInt64Size(1, event.playerId())
                    + CodedOutputStream.computeByteArraySize(3, snapshot);
            for (ProfileField field : event.changedFields()) {
                size += stringSize(2, field.name());
            }
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, event.playerId());
                for (ProfileField field : event.changedFields()) {
                    output.writeString(2, field.name());
                }
                output.writeByteArray(3, snapshot);
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode profile changed event", e);
            }
        }

        @Override
        public ProfileChangedEvent decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long playerId = 0;
            Set<ProfileField> fields = new HashSet<>();
            PlayerProfileSnapshot snapshot = null;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> playerId = input.readInt64();
                        case 2 -> fields.add(ProfileField.valueOf(input.readString()));
                        case 3 -> snapshot = decodeSnapshot(input.readByteArray());
                        default -> input.skipField(tag);
                    }
                }
                return new ProfileChangedEvent(playerId, fields, snapshot);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode profile changed event", e);
            }
        }
    }

    private static final class AllianceMemberChangedEventCodec implements EventCodec<AllianceMemberChangedEvent> {
        @Override
        public String typeName() {
            return AllianceMemberChangedEvent.class.getName();
        }

        @Override
        public byte[] encode(AllianceMemberChangedEvent event) {
            int size = CodedOutputStream.computeInt64Size(1, event.allianceId())
                    + CodedOutputStream.computeInt64Size(2, event.playerId())
                    + stringSize(3, event.action().name())
                    + CodedOutputStream.computeInt64Size(4, event.revision());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, event.allianceId());
                output.writeInt64(2, event.playerId());
                output.writeString(3, event.action().name());
                output.writeInt64(4, event.revision());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode alliance member changed event", e);
            }
        }

        @Override
        public AllianceMemberChangedEvent decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long allianceId = 0;
            long playerId = 0;
            AllianceMemberAction action = AllianceMemberAction.JOIN;
            long revision = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> allianceId = input.readInt64();
                        case 2 -> playerId = input.readInt64();
                        case 3 -> action = AllianceMemberAction.valueOf(input.readString());
                        case 4 -> revision = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                return new AllianceMemberChangedEvent(allianceId, playerId, action, revision);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode alliance member changed event", e);
            }
        }
    }

    private record Subscription(ServiceId subscriber, String topic) {
    }

    private static byte[] encodeSubscription(ServiceId subscriber, String topic) {
        int size = stringSize(1, subscriber.kind().name())
                + stringSize(2, subscriber.region())
                + stringSize(3, subscriber.node())
                + stringSize(4, topic);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, subscriber.kind().name());
            output.writeString(2, subscriber.region());
            output.writeString(3, subscriber.node());
            output.writeString(4, topic);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode event subscription", e);
        }
    }

    private static Subscription decodeSubscription(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String kind = ServiceKind.CENTER.name();
        String region = "";
        String node = "";
        String topic = "";
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> kind = input.readString();
                    case 2 -> region = input.readString();
                    case 3 -> node = input.readString();
                    case 4 -> topic = input.readString();
                    default -> input.skipField(tag);
                }
            }
            return new Subscription(ServiceId.of(ServiceKind.valueOf(kind), region, node), topic);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode event subscription", e);
        }
    }

    private static byte[] encodeSnapshot(PlayerProfileSnapshot snapshot) {
        byte[] appearance = encodeAppearance(snapshot.appearance());
        byte[] alliance = encodeAlliance(snapshot.alliance());
        byte[] friends = encodeFriends(snapshot.friends());
        int size = CodedOutputStream.computeInt64Size(1, snapshot.playerId())
                + stringSize(2, snapshot.name())
                + CodedOutputStream.computeInt32Size(3, snapshot.level())
                + CodedOutputStream.computeByteArraySize(4, appearance)
                + CodedOutputStream.computeByteArraySize(5, alliance)
                + CodedOutputStream.computeByteArraySize(6, friends)
                + CodedOutputStream.computeInt64Size(7, snapshot.revision())
                + CodedOutputStream.computeInt64Size(8, snapshot.updatedAt().getEpochSecond())
                + CodedOutputStream.computeInt32Size(9, snapshot.updatedAt().getNano());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, snapshot.playerId());
            output.writeString(2, snapshot.name());
            output.writeInt32(3, snapshot.level());
            output.writeByteArray(4, appearance);
            output.writeByteArray(5, alliance);
            output.writeByteArray(6, friends);
            output.writeInt64(7, snapshot.revision());
            output.writeInt64(8, snapshot.updatedAt().getEpochSecond());
            output.writeInt32(9, snapshot.updatedAt().getNano());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode profile snapshot", e);
        }
    }

    private static PlayerProfileSnapshot decodeSnapshot(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long playerId = 0;
        String name = "";
        int level = 1;
        AppearanceSummary appearance = AppearanceSummary.defaults();
        AllianceBrief alliance = AllianceBrief.none();
        FriendBrief friends = new FriendBrief();
        long revision = 0;
        long epochSecond = 0;
        int nano = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> playerId = input.readInt64();
                    case 2 -> name = input.readString();
                    case 3 -> level = input.readInt32();
                    case 4 -> appearance = decodeAppearance(input.readByteArray());
                    case 5 -> alliance = decodeAlliance(input.readByteArray());
                    case 6 -> friends = decodeFriends(input.readByteArray());
                    case 7 -> revision = input.readInt64();
                    case 8 -> epochSecond = input.readInt64();
                    case 9 -> nano = input.readInt32();
                    default -> input.skipField(tag);
                }
            }
            return new PlayerProfileSnapshot(playerId, name, level, appearance, alliance, friends, revision,
                    Instant.ofEpochSecond(epochSecond, nano));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode profile snapshot", e);
        }
    }

    private static byte[] encodeAppearance(AppearanceSummary appearance) {
        int size = stringSize(1, appearance.avatar())
                + stringSize(2, appearance.frame())
                + stringSize(3, appearance.costume());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, appearance.avatar());
            output.writeString(2, appearance.frame());
            output.writeString(3, appearance.costume());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode appearance", e);
        }
    }

    private static AppearanceSummary decodeAppearance(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String avatar = "";
        String frame = "";
        String costume = "";
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> avatar = input.readString();
                    case 2 -> frame = input.readString();
                    case 3 -> costume = input.readString();
                    default -> input.skipField(tag);
                }
            }
            return new AppearanceSummary(avatar, frame, costume);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode appearance", e);
        }
    }

    private static byte[] encodeAlliance(AllianceBrief alliance) {
        int size = CodedOutputStream.computeInt64Size(1, alliance.allianceId())
                + stringSize(2, alliance.name())
                + stringSize(3, alliance.badge());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, alliance.allianceId());
            output.writeString(2, alliance.name());
            output.writeString(3, alliance.badge());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode alliance brief", e);
        }
    }

    private static AllianceBrief decodeAlliance(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long allianceId = 0;
        String name = "";
        String badge = "";
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> allianceId = input.readInt64();
                    case 2 -> name = input.readString();
                    case 3 -> badge = input.readString();
                    default -> input.skipField(tag);
                }
            }
            return new AllianceBrief(allianceId, name, badge);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode alliance brief", e);
        }
    }

    private static byte[] encodeFriends(FriendBrief friends) {
        int size = CodedOutputStream.computeInt32Size(1, friends.friendCount())
                + CodedOutputStream.computeInt64Size(2, friends.socialRevision());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt32(1, friends.friendCount());
            output.writeInt64(2, friends.socialRevision());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode friend brief", e);
        }
    }

    private static FriendBrief decodeFriends(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        int friendCount = 0;
        long socialRevision = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> friendCount = input.readInt32();
                    case 2 -> socialRevision = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return new FriendBrief(friendCount, socialRevision);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode friend brief", e);
        }
    }

    private static int stringSize(int fieldNumber, String value) {
        return CodedOutputStream.computeStringSize(fieldNumber, value);
    }
}
