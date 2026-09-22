package com.commonbattle.cluster.event;

import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.protocol.PayloadCodec;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.activity.ActivitySchedule;
import com.commonbattle.game.activity.ActivityType;
import com.commonbattle.game.activity.AlwaysOpenSchedule;
import com.commonbattle.game.activity.AlwaysParticipationCondition;
import com.commonbattle.game.activity.MinLevelCondition;
import com.commonbattle.game.activity.NaturalTimeSchedule;
import com.commonbattle.game.activity.OpenServerTimeSchedule;
import com.commonbattle.game.activity.ParticipationCondition;
import com.commonbattle.game.achievement.AchievementDefinition;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.battle.BattleStageDefinition;
import com.commonbattle.game.config.GameConfigChangeType;
import com.commonbattle.game.config.GameConfigChangedEvent;
import com.commonbattle.game.config.GameConfigPackage;
import com.commonbattle.game.config.GameConfigSnapshot;
import com.commonbattle.game.config.GameConfigSnapshotRequest;
import com.commonbattle.game.config.GrowthTuning;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import com.commonbattle.game.profile.ProfileSnapshotRequest;
import com.commonbattle.game.profile.ProfileSnapshotResponse;
import com.commonbattle.game.player.event.EventProgressRule;
import com.commonbattle.game.player.event.PlayerDomainProjectionSnapshot;
import com.commonbattle.game.player.event.PlayerDomainProjectionSnapshotRequest;
import com.commonbattle.game.player.event.PlayerDomainProjectionSnapshotResponse;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.social.AllianceMemberAction;
import com.commonbattle.game.social.AllianceMemberChangedEvent;
import com.commonbattle.game.social.AllianceSnapshot;
import com.commonbattle.game.social.AllianceSnapshotRequest;
import com.commonbattle.game.social.AllianceSnapshotResponse;
import com.commonbattle.game.social.FriendChangedEvent;
import com.commonbattle.game.social.FriendRelationAction;
import com.commonbattle.game.social.FriendSnapshot;
import com.commonbattle.game.social.FriendSnapshotRequest;
import com.commonbattle.game.social.FriendSnapshotResponse;
import com.commonbattle.game.shop.ShopItemDefinition;
import com.commonbattle.game.task.TaskDefinition;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨服事件通道 payload 注册入口。
 * 事件请求使用显式 wire codec，避免 Java record、接口字段和运行时 Bean 序列化影响灰度兼容。
 */
public final class ClusterEventPayloadCodecs {
    private static final Map<Class<?>, EventCodec<?>> EVENT_BY_CLASS = Map.of(
            ProfileChangedEvent.class, new ProfileChangedEventCodec(),
            AllianceMemberChangedEvent.class, new AllianceMemberChangedEventCodec(),
            FriendChangedEvent.class, new FriendChangedEventCodec(),
            GameConfigChangedEvent.class, new GameConfigChangedEventCodec(),
            PlayerDomainVersionedEvent.class, new PlayerDomainVersionedEventCodec()
    );
    private static final Map<String, EventCodec<?>> EVENT_BY_TYPE = indexByType();

    private ClusterEventPayloadCodecs() {
    }

    public static PayloadCodecRegistry registerTo(PayloadCodecRegistry registry) {
        registry.register(new EventSubscribeRequestCodec());
        registry.register(new EventUnsubscribeRequestCodec());
        registry.register(new EventPublishRequestCodec());
        registry.register(new EventDeliverRequestCodec());
        registry.register(new EventReplayRequestCodec());
        registry.register(new EventReplayResultCodec());
        registry.register(new ProfileSnapshotRequestCodec());
        registry.register(new ProfileSnapshotResponseCodec());
        registry.register(new AllianceSnapshotRequestCodec());
        registry.register(new AllianceSnapshotResponseCodec());
        registry.register(new FriendSnapshotRequestCodec());
        registry.register(new FriendSnapshotResponseCodec());
        registry.register(new PlayerDomainProjectionSnapshotRequestCodec());
        registry.register(new PlayerDomainProjectionSnapshotResponseCodec());
        registry.register(new GameConfigSnapshotRequestCodec());
        registry.register(new GameConfigSnapshotCodec());
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
            return encodeSubscription(payload.subscriber(), payload.topic(), payload.ownerKeys(), payload.leaseTtl());
        }

        @Override
        public EventSubscribeRequest decode(byte[] bytes) {
            Subscription subscription = decodeSubscription(bytes);
            return new EventSubscribeRequest(
                    subscription.subscriber(),
                    subscription.topic(),
                    subscription.ownerKeys(),
                    subscription.leaseTtl()
            );
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
            return encodeSubscription(payload.subscriber(), payload.topic(), payload.ownerKeys(), Duration.ZERO);
        }

        @Override
        public EventUnsubscribeRequest decode(byte[] bytes) {
            Subscription subscription = decodeSubscription(bytes);
            return new EventUnsubscribeRequest(subscription.subscriber(), subscription.topic(), subscription.ownerKeys());
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

    private static final class EventReplayRequestCodec implements PayloadCodec<EventReplayRequest> {
        @Override
        public String typeName() {
            return EventReplayRequest.class.getName();
        }

        @Override
        public Class<EventReplayRequest> javaType() {
            return EventReplayRequest.class;
        }

        @Override
        public byte[] encode(EventReplayRequest payload) {
            int size = stringSize(1, payload.subscriber().kind().name())
                    + stringSize(2, payload.subscriber().region())
                    + stringSize(3, payload.subscriber().node())
                    + stringSize(4, payload.topic());
            for (Map.Entry<String, Long> entry : payload.knownRevisions().entrySet()) {
                size += CodedOutputStream.computeByteArraySize(5, encodeKnownRevision(entry.getKey(), entry.getValue()));
            }
            for (String ownerKey : payload.ownerKeys()) {
                size += stringSize(6, ownerKey);
            }
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeString(1, payload.subscriber().kind().name());
                output.writeString(2, payload.subscriber().region());
                output.writeString(3, payload.subscriber().node());
                output.writeString(4, payload.topic());
                for (Map.Entry<String, Long> entry : payload.knownRevisions().entrySet()) {
                    output.writeByteArray(5, encodeKnownRevision(entry.getKey(), entry.getValue()));
                }
                for (String ownerKey : payload.ownerKeys()) {
                    output.writeString(6, ownerKey);
                }
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode event replay request", e);
            }
        }

        @Override
        public EventReplayRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            String kind = ServiceKind.CENTER.name();
            String region = "default";
            String node = "default";
            String topic = "";
            Map<String, Long> knownRevisions = new HashMap<>();
            Set<String> ownerKeys = new HashSet<>();
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> kind = input.readString();
                        case 2 -> region = input.readString();
                        case 3 -> node = input.readString();
                        case 4 -> topic = input.readString();
                        case 5 -> {
                            KnownRevision revision = decodeKnownRevision(input.readByteArray());
                            knownRevisions.put(revision.ownerKey(), revision.revision());
                        }
                        case 6 -> ownerKeys.add(input.readString());
                        default -> input.skipField(tag);
                    }
                }
                return new EventReplayRequest(ServiceId.of(ServiceKind.valueOf(kind), region, node), topic, knownRevisions, ownerKeys);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode event replay request", e);
            }
        }
    }

    private static final class EventReplayResultCodec implements PayloadCodec<EventReplayResult> {
        @Override
        public String typeName() {
            return EventReplayResult.class.getName();
        }

        @Override
        public Class<EventReplayResult> javaType() {
            return EventReplayResult.class;
        }

        @Override
        public byte[] encode(EventReplayResult payload) {
            int size = CodedOutputStream.computeInt32Size(1, payload.delivered())
                    + CodedOutputStream.computeInt32Size(2, payload.unavailableOwners());
            for (String ownerKey : payload.unavailableOwnerKeys()) {
                size += stringSize(3, ownerKey);
            }
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt32(1, payload.delivered());
                output.writeInt32(2, payload.unavailableOwners());
                for (String ownerKey : payload.unavailableOwnerKeys()) {
                    output.writeString(3, ownerKey);
                }
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode event replay result", e);
            }
        }

        @Override
        public EventReplayResult decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            int delivered = 0;
            int unavailableOwners = 0;
            Set<String> unavailableOwnerKeys = new HashSet<>();
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> delivered = input.readInt32();
                        case 2 -> unavailableOwners = input.readInt32();
                        case 3 -> unavailableOwnerKeys.add(input.readString());
                        default -> input.skipField(tag);
                    }
                }
                return new EventReplayResult(delivered, unavailableOwners, unavailableOwnerKeys);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode event replay result", e);
            }
        }
    }

    private static final class ProfileSnapshotRequestCodec implements PayloadCodec<ProfileSnapshotRequest> {
        @Override
        public String typeName() {
            return ProfileSnapshotRequest.class.getName();
        }

        @Override
        public Class<ProfileSnapshotRequest> javaType() {
            return ProfileSnapshotRequest.class;
        }

        @Override
        public byte[] encode(ProfileSnapshotRequest payload) {
            int size = CodedOutputStream.computeInt64Size(1, payload.playerId());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, payload.playerId());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode profile snapshot request", e);
            }
        }

        @Override
        public ProfileSnapshotRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long playerId = 1;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> playerId = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                return new ProfileSnapshotRequest(playerId);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode profile snapshot request", e);
            }
        }
    }

    private static final class ProfileSnapshotResponseCodec implements PayloadCodec<ProfileSnapshotResponse> {
        @Override
        public String typeName() {
            return ProfileSnapshotResponse.class.getName();
        }

        @Override
        public Class<ProfileSnapshotResponse> javaType() {
            return ProfileSnapshotResponse.class;
        }

        @Override
        public byte[] encode(ProfileSnapshotResponse payload) {
            byte[] snapshot = payload.snapshot() == null ? null : encodeSnapshot(payload.snapshot());
            int size = CodedOutputStream.computeBoolSize(1, payload.found());
            if (snapshot != null) {
                size += CodedOutputStream.computeByteArraySize(2, snapshot);
            }
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeBool(1, payload.found());
                if (snapshot != null) {
                    output.writeByteArray(2, snapshot);
                }
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode profile snapshot response", e);
            }
        }

        @Override
        public ProfileSnapshotResponse decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            boolean found = false;
            PlayerProfileSnapshot snapshot = null;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> found = input.readBool();
                        case 2 -> snapshot = decodeSnapshot(input.readByteArray());
                        default -> input.skipField(tag);
                    }
                }
                return found ? ProfileSnapshotResponse.found(snapshot) : ProfileSnapshotResponse.missing();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode profile snapshot response", e);
            }
        }
    }

    private static final class AllianceSnapshotRequestCodec implements PayloadCodec<AllianceSnapshotRequest> {
        @Override
        public String typeName() {
            return AllianceSnapshotRequest.class.getName();
        }

        @Override
        public Class<AllianceSnapshotRequest> javaType() {
            return AllianceSnapshotRequest.class;
        }

        @Override
        public byte[] encode(AllianceSnapshotRequest payload) {
            int size = CodedOutputStream.computeInt64Size(1, payload.allianceId());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, payload.allianceId());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode alliance snapshot request", e);
            }
        }

        @Override
        public AllianceSnapshotRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long allianceId = 1;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> allianceId = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                return new AllianceSnapshotRequest(allianceId);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode alliance snapshot request", e);
            }
        }
    }

    private static final class AllianceSnapshotResponseCodec implements PayloadCodec<AllianceSnapshotResponse> {
        @Override
        public String typeName() {
            return AllianceSnapshotResponse.class.getName();
        }

        @Override
        public Class<AllianceSnapshotResponse> javaType() {
            return AllianceSnapshotResponse.class;
        }

        @Override
        public byte[] encode(AllianceSnapshotResponse payload) {
            byte[] snapshot = payload.snapshot() == null ? null : encodeAllianceSnapshot(payload.snapshot());
            int size = CodedOutputStream.computeBoolSize(1, payload.found());
            if (snapshot != null) {
                size += CodedOutputStream.computeByteArraySize(2, snapshot);
            }
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeBool(1, payload.found());
                if (snapshot != null) {
                    output.writeByteArray(2, snapshot);
                }
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode alliance snapshot response", e);
            }
        }

        @Override
        public AllianceSnapshotResponse decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            boolean found = false;
            AllianceSnapshot snapshot = null;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> found = input.readBool();
                        case 2 -> snapshot = decodeAllianceSnapshot(input.readByteArray());
                        default -> input.skipField(tag);
                    }
                }
                return new AllianceSnapshotResponse(found, snapshot);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode alliance snapshot response", e);
            }
        }
    }

    private static final class FriendSnapshotRequestCodec implements PayloadCodec<FriendSnapshotRequest> {
        @Override
        public String typeName() {
            return FriendSnapshotRequest.class.getName();
        }

        @Override
        public Class<FriendSnapshotRequest> javaType() {
            return FriendSnapshotRequest.class;
        }

        @Override
        public byte[] encode(FriendSnapshotRequest payload) {
            int size = CodedOutputStream.computeInt64Size(1, payload.playerId());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, payload.playerId());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode friend snapshot request", e);
            }
        }

        @Override
        public FriendSnapshotRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long playerId = 1;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> playerId = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                return new FriendSnapshotRequest(playerId);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode friend snapshot request", e);
            }
        }
    }

    private static final class FriendSnapshotResponseCodec implements PayloadCodec<FriendSnapshotResponse> {
        @Override
        public String typeName() {
            return FriendSnapshotResponse.class.getName();
        }

        @Override
        public Class<FriendSnapshotResponse> javaType() {
            return FriendSnapshotResponse.class;
        }

        @Override
        public byte[] encode(FriendSnapshotResponse payload) {
            byte[] snapshot = payload.snapshot() == null ? null : encodeFriendSnapshot(payload.snapshot());
            int size = CodedOutputStream.computeBoolSize(1, payload.found());
            if (snapshot != null) {
                size += CodedOutputStream.computeByteArraySize(2, snapshot);
            }
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeBool(1, payload.found());
                if (snapshot != null) {
                    output.writeByteArray(2, snapshot);
                }
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode friend snapshot response", e);
            }
        }

        @Override
        public FriendSnapshotResponse decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            boolean found = false;
            FriendSnapshot snapshot = null;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> found = input.readBool();
                        case 2 -> snapshot = decodeFriendSnapshot(input.readByteArray());
                        default -> input.skipField(tag);
                    }
                }
                return new FriendSnapshotResponse(found, snapshot);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode friend snapshot response", e);
            }
        }
    }

    private static final class PlayerDomainProjectionSnapshotRequestCodec
            implements PayloadCodec<PlayerDomainProjectionSnapshotRequest> {
        @Override
        public String typeName() {
            return PlayerDomainProjectionSnapshotRequest.class.getName();
        }

        @Override
        public Class<PlayerDomainProjectionSnapshotRequest> javaType() {
            return PlayerDomainProjectionSnapshotRequest.class;
        }

        @Override
        public byte[] encode(PlayerDomainProjectionSnapshotRequest payload) {
            int size = CodedOutputStream.computeInt64Size(1, payload.playerId());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, payload.playerId());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player domain projection snapshot request", e);
            }
        }

        @Override
        public PlayerDomainProjectionSnapshotRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long playerId = 1;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> playerId = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                return new PlayerDomainProjectionSnapshotRequest(playerId);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player domain projection snapshot request", e);
            }
        }
    }

    private static final class PlayerDomainProjectionSnapshotResponseCodec
            implements PayloadCodec<PlayerDomainProjectionSnapshotResponse> {
        @Override
        public String typeName() {
            return PlayerDomainProjectionSnapshotResponse.class.getName();
        }

        @Override
        public Class<PlayerDomainProjectionSnapshotResponse> javaType() {
            return PlayerDomainProjectionSnapshotResponse.class;
        }

        @Override
        public byte[] encode(PlayerDomainProjectionSnapshotResponse payload) {
            byte[] snapshot = payload.snapshot() == null ? null : encodePlayerDomainProjectionSnapshot(payload.snapshot());
            int size = CodedOutputStream.computeBoolSize(1, payload.found());
            if (snapshot != null) {
                size += CodedOutputStream.computeByteArraySize(2, snapshot);
            }
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeBool(1, payload.found());
                if (snapshot != null) {
                    output.writeByteArray(2, snapshot);
                }
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player domain projection snapshot response", e);
            }
        }

        @Override
        public PlayerDomainProjectionSnapshotResponse decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            boolean found = false;
            PlayerDomainProjectionSnapshot snapshot = null;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> found = input.readBool();
                        case 2 -> snapshot = decodePlayerDomainProjectionSnapshot(input.readByteArray());
                        default -> input.skipField(tag);
                    }
                }
                return new PlayerDomainProjectionSnapshotResponse(found, snapshot);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player domain projection snapshot response", e);
            }
        }
    }

    private static final class GameConfigSnapshotRequestCodec implements PayloadCodec<GameConfigSnapshotRequest> {
        @Override
        public String typeName() {
            return GameConfigSnapshotRequest.class.getName();
        }

        @Override
        public Class<GameConfigSnapshotRequest> javaType() {
            return GameConfigSnapshotRequest.class;
        }

        @Override
        public byte[] encode(GameConfigSnapshotRequest payload) {
            int size = CodedOutputStream.computeInt64Size(1, payload.knownEventRevision());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, payload.knownEventRevision());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode config snapshot request", e);
            }
        }

        @Override
        public GameConfigSnapshotRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long revision = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> revision = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                return new GameConfigSnapshotRequest(revision);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode config snapshot request", e);
            }
        }
    }

    private static final class GameConfigSnapshotCodec implements PayloadCodec<GameConfigSnapshot> {
        @Override
        public String typeName() {
            return GameConfigSnapshot.class.getName();
        }

        @Override
        public Class<GameConfigSnapshot> javaType() {
            return GameConfigSnapshot.class;
        }

        @Override
        public byte[] encode(GameConfigSnapshot payload) {
            byte[] active = encodeConfigPackage(payload.activeConfig());
            byte[] gray = payload.grayConfig() == null ? null : encodeConfigPackage(payload.grayConfig());
            int size = CodedOutputStream.computeInt64Size(1, payload.eventRevision())
                    + CodedOutputStream.computeByteArraySize(2, active)
                    + CodedOutputStream.computeInt32Size(4, payload.grayPercent());
            if (gray != null) {
                size += CodedOutputStream.computeByteArraySize(3, gray);
            }
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, payload.eventRevision());
                output.writeByteArray(2, active);
                if (gray != null) {
                    output.writeByteArray(3, gray);
                }
                output.writeInt32(4, payload.grayPercent());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode config snapshot", e);
            }
        }

        @Override
        public GameConfigSnapshot decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long eventRevision = 0;
            GameConfigPackage active = null;
            GameConfigPackage gray = null;
            int grayPercent = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> eventRevision = input.readInt64();
                        case 2 -> active = decodeConfigPackage(input.readByteArray());
                        case 3 -> gray = decodeConfigPackage(input.readByteArray());
                        case 4 -> grayPercent = input.readInt32();
                        default -> input.skipField(tag);
                    }
                }
                return new GameConfigSnapshot(eventRevision, active, gray, grayPercent);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode config snapshot", e);
            }
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

    private static final class FriendChangedEventCodec implements EventCodec<FriendChangedEvent> {
        @Override
        public String typeName() {
            return FriendChangedEvent.class.getName();
        }

        @Override
        public byte[] encode(FriendChangedEvent event) {
            int size = CodedOutputStream.computeInt64Size(1, event.playerId())
                    + CodedOutputStream.computeInt64Size(2, event.friendId())
                    + stringSize(3, event.action().name())
                    + CodedOutputStream.computeInt64Size(4, event.revision());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, event.playerId());
                output.writeInt64(2, event.friendId());
                output.writeString(3, event.action().name());
                output.writeInt64(4, event.revision());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode friend changed event", e);
            }
        }

        @Override
        public FriendChangedEvent decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long playerId = 0;
            long friendId = 0;
            FriendRelationAction action = FriendRelationAction.ADD;
            long revision = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> playerId = input.readInt64();
                        case 2 -> friendId = input.readInt64();
                        case 3 -> action = FriendRelationAction.valueOf(input.readString());
                        case 4 -> revision = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                return new FriendChangedEvent(playerId, friendId, action, revision);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode friend changed event", e);
            }
        }
    }

    private static final class GameConfigChangedEventCodec implements EventCodec<GameConfigChangedEvent> {
        @Override
        public String typeName() {
            return GameConfigChangedEvent.class.getName();
        }

        @Override
        public byte[] encode(GameConfigChangedEvent event) {
            byte[] config = encodeConfigPackage(event.config());
            int size = CodedOutputStream.computeInt64Size(1, event.revision())
                    + stringSize(2, event.changeType().name())
                    + CodedOutputStream.computeByteArraySize(3, config)
                    + CodedOutputStream.computeInt32Size(4, event.grayPercent());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, event.revision());
                output.writeString(2, event.changeType().name());
                output.writeByteArray(3, config);
                output.writeInt32(4, event.grayPercent());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode game config event", e);
            }
        }

        @Override
        public GameConfigChangedEvent decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long revision = 0;
            GameConfigChangeType changeType = GameConfigChangeType.ACTIVE_PUBLISHED;
            GameConfigPackage config = null;
            int grayPercent = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> revision = input.readInt64();
                        case 2 -> changeType = GameConfigChangeType.valueOf(input.readString());
                        case 3 -> config = decodeConfigPackage(input.readByteArray());
                        case 4 -> grayPercent = input.readInt32();
                        default -> input.skipField(tag);
                    }
                }
                return new GameConfigChangedEvent(revision, changeType, config, grayPercent);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode game config event", e);
            }
        }
    }

    private static final class PlayerDomainVersionedEventCodec implements EventCodec<PlayerDomainVersionedEvent> {
        @Override
        public String typeName() {
            return PlayerDomainVersionedEvent.class.getName();
        }

        @Override
        public byte[] encode(PlayerDomainVersionedEvent event) {
            int size = CodedOutputStream.computeInt64Size(1, event.playerId())
                    + stringSize(2, event.eventType())
                    + stringSize(3, event.subject())
                    + CodedOutputStream.computeInt32Size(4, event.delta())
                    + CodedOutputStream.computeInt64Size(5, event.revision())
                    + CodedOutputStream.computeInt64Size(6, event.occurredAt().getEpochSecond())
                    + CodedOutputStream.computeInt32Size(7, event.occurredAt().getNano());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, event.playerId());
                output.writeString(2, event.eventType());
                output.writeString(3, event.subject());
                output.writeInt32(4, event.delta());
                output.writeInt64(5, event.revision());
                output.writeInt64(6, event.occurredAt().getEpochSecond());
                output.writeInt32(7, event.occurredAt().getNano());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player domain event", e);
            }
        }

        @Override
        public PlayerDomainVersionedEvent decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long playerId = 0;
            String eventType = "";
            String subject = "";
            int delta = 1;
            long revision = 1;
            long epochSecond = 0;
            int nano = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> playerId = input.readInt64();
                        case 2 -> eventType = input.readString();
                        case 3 -> subject = input.readString();
                        case 4 -> delta = input.readInt32();
                        case 5 -> revision = input.readInt64();
                        case 6 -> epochSecond = input.readInt64();
                        case 7 -> nano = input.readInt32();
                        default -> input.skipField(tag);
                    }
                }
                return new PlayerDomainVersionedEvent(
                        playerId,
                        eventType,
                        subject,
                        delta,
                        revision,
                        Instant.ofEpochSecond(epochSecond, nano)
                );
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player domain event", e);
            }
        }
    }

    private record Subscription(ServiceId subscriber, String topic, Set<String> ownerKeys, Duration leaseTtl) {
    }

    private record KnownRevision(String ownerKey, long revision) {
    }

    private static byte[] encodeConfigPackage(GameConfigPackage config) {
        byte[] growth = encodeGrowth(config.growth());
        int size = CodedOutputStream.computeInt64Size(1, config.version())
                + CodedOutputStream.computeByteArraySize(4, growth)
                + CodedOutputStream.computeInt64Size(5, config.createdAt().getEpochSecond())
                + CodedOutputStream.computeInt32Size(6, config.createdAt().getNano());
        for (ItemDefinition item : config.items()) {
            size += CodedOutputStream.computeByteArraySize(2, encodeItemDefinition(item));
        }
        for (ActivityDefinition activity : config.activities()) {
            size += CodedOutputStream.computeByteArraySize(3, encodeActivityDefinition(activity));
        }
        for (ShopItemDefinition shop : config.shops()) {
            size += CodedOutputStream.computeByteArraySize(7, encodeShopItemDefinition(shop));
        }
        for (BattleStageDefinition battle : config.battles()) {
            size += CodedOutputStream.computeByteArraySize(8, encodeBattleStageDefinition(battle));
        }
        for (TaskDefinition task : config.tasks()) {
            size += CodedOutputStream.computeByteArraySize(9, encodeTaskDefinition(task));
        }
        for (AchievementDefinition achievement : config.achievements()) {
            size += CodedOutputStream.computeByteArraySize(10, encodeAchievementDefinition(achievement));
        }
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, config.version());
            for (ItemDefinition item : config.items()) {
                output.writeByteArray(2, encodeItemDefinition(item));
            }
            for (ActivityDefinition activity : config.activities()) {
                output.writeByteArray(3, encodeActivityDefinition(activity));
            }
            output.writeByteArray(4, growth);
            output.writeInt64(5, config.createdAt().getEpochSecond());
            output.writeInt32(6, config.createdAt().getNano());
            for (ShopItemDefinition shop : config.shops()) {
                output.writeByteArray(7, encodeShopItemDefinition(shop));
            }
            for (BattleStageDefinition battle : config.battles()) {
                output.writeByteArray(8, encodeBattleStageDefinition(battle));
            }
            for (TaskDefinition task : config.tasks()) {
                output.writeByteArray(9, encodeTaskDefinition(task));
            }
            for (AchievementDefinition achievement : config.achievements()) {
                output.writeByteArray(10, encodeAchievementDefinition(achievement));
            }
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode config package", e);
        }
    }

    private static GameConfigPackage decodeConfigPackage(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long version = 0;
        List<ItemDefinition> items = new ArrayList<>();
        List<ActivityDefinition> activities = new ArrayList<>();
        List<ShopItemDefinition> shops = new ArrayList<>();
        List<BattleStageDefinition> battles = new ArrayList<>();
        List<TaskDefinition> tasks = new ArrayList<>();
        List<AchievementDefinition> achievements = new ArrayList<>();
        GrowthTuning growth = null;
        long epochSecond = 0;
        int nano = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> version = input.readInt64();
                    case 2 -> items.add(decodeItemDefinition(input.readByteArray()));
                    case 3 -> activities.add(decodeActivityDefinition(input.readByteArray()));
                    case 4 -> growth = decodeGrowth(input.readByteArray());
                    case 5 -> epochSecond = input.readInt64();
                    case 6 -> nano = input.readInt32();
                    case 7 -> shops.add(decodeShopItemDefinition(input.readByteArray()));
                    case 8 -> battles.add(decodeBattleStageDefinition(input.readByteArray()));
                    case 9 -> tasks.add(decodeTaskDefinition(input.readByteArray()));
                    case 10 -> achievements.add(decodeAchievementDefinition(input.readByteArray()));
                    default -> input.skipField(tag);
                }
            }
            return new GameConfigPackage(version, items, activities, shops, battles, tasks, achievements, growth,
                    Instant.ofEpochSecond(epochSecond, nano));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode config package", e);
        }
    }

    private static byte[] encodeItemDefinition(ItemDefinition item) {
        int size = stringSize(1, item.itemId())
                + stringSize(2, item.type())
                + CodedOutputStream.computeInt32Size(3, item.stackLimit());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, item.itemId());
            output.writeString(2, item.type());
            output.writeInt32(3, item.stackLimit());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode item definition", e);
        }
    }

    private static ItemDefinition decodeItemDefinition(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String itemId = "";
        String type = "";
        int stackLimit = 1;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> itemId = input.readString();
                    case 2 -> type = input.readString();
                    case 3 -> stackLimit = input.readInt32();
                    default -> input.skipField(tag);
                }
            }
            return new ItemDefinition(itemId, type, stackLimit);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode item definition", e);
        }
    }

    private static byte[] encodeActivityDefinition(ActivityDefinition activity) {
        byte[] reward = encodeReward(activity.reward());
        byte[] schedule = encodeSchedule(activity.schedule());
        byte[] participation = encodeParticipation(activity.participation());
        int size = stringSize(1, activity.activityId())
                + stringSize(2, activity.type().name())
                + CodedOutputStream.computeInt32Size(3, activity.threshold())
                + CodedOutputStream.computeByteArraySize(4, reward)
                + CodedOutputStream.computeByteArraySize(5, schedule)
                + CodedOutputStream.computeByteArraySize(6, participation)
                + CodedOutputStream.computeByteArraySize(7, encodeEventProgressRule(activity.progressRule()));
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, activity.activityId());
            output.writeString(2, activity.type().name());
            output.writeInt32(3, activity.threshold());
            output.writeByteArray(4, reward);
            output.writeByteArray(5, schedule);
            output.writeByteArray(6, participation);
            output.writeByteArray(7, encodeEventProgressRule(activity.progressRule()));
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode activity definition", e);
        }
    }

    private static ActivityDefinition decodeActivityDefinition(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String activityId = "";
        ActivityType type = ActivityType.LOGIN;
        int threshold = 1;
        Reward reward = Reward.of();
        ActivitySchedule schedule = ActivitySchedule.alwaysOpen();
        ParticipationCondition participation = ParticipationCondition.always();
        EventProgressRule progressRule = EventProgressRule.none();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> activityId = input.readString();
                    case 2 -> type = ActivityType.valueOf(input.readString());
                    case 3 -> threshold = input.readInt32();
                    case 4 -> reward = decodeReward(input.readByteArray());
                    case 5 -> schedule = decodeSchedule(input.readByteArray());
                    case 6 -> participation = decodeParticipation(input.readByteArray());
                    case 7 -> progressRule = decodeEventProgressRule(input.readByteArray());
                    default -> input.skipField(tag);
                }
            }
            return new ActivityDefinition(activityId, type, threshold, reward, schedule, participation, progressRule);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode activity definition", e);
        }
    }

    private static byte[] encodeShopItemDefinition(ShopItemDefinition shop) {
        byte[] price = encodeItemStack(shop.price());
        byte[] reward = encodeReward(shop.reward());
        int size = stringSize(1, shop.sku())
                + CodedOutputStream.computeByteArraySize(2, price)
                + CodedOutputStream.computeByteArraySize(3, reward)
                + CodedOutputStream.computeInt32Size(4, shop.lifetimeLimit())
                + CodedOutputStream.computeInt32Size(5, shop.dailyLimit())
                + CodedOutputStream.computeInt32Size(6, shop.globalStock());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, shop.sku());
            output.writeByteArray(2, price);
            output.writeByteArray(3, reward);
            output.writeInt32(4, shop.lifetimeLimit());
            output.writeInt32(5, shop.dailyLimit());
            output.writeInt32(6, shop.globalStock());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode shop item definition", e);
        }
    }

    private static ShopItemDefinition decodeShopItemDefinition(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String sku = "";
        ItemStack price = null;
        Reward reward = null;
        int lifetimeLimit = 0;
        int dailyLimit = 0;
        int globalStock = ShopItemDefinition.UNLIMITED_STOCK;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> sku = input.readString();
                    case 2 -> price = decodeItemStack(input.readByteArray());
                    case 3 -> reward = decodeReward(input.readByteArray());
                    case 4 -> lifetimeLimit = input.readInt32();
                    case 5 -> dailyLimit = input.readInt32();
                    case 6 -> globalStock = input.readInt32();
                    default -> input.skipField(tag);
                }
            }
            return new ShopItemDefinition(sku, price, reward, lifetimeLimit, dailyLimit, globalStock);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode shop item definition", e);
        }
    }

    private static byte[] encodeBattleStageDefinition(BattleStageDefinition battle) {
        byte[] reward = encodeReward(battle.victoryReward());
        int size = stringSize(1, battle.stageId())
                + CodedOutputStream.computeInt32Size(2, battle.playerHp())
                + CodedOutputStream.computeInt32Size(3, battle.playerAttack())
                + CodedOutputStream.computeInt32Size(4, battle.enemyHp())
                + CodedOutputStream.computeInt32Size(5, battle.enemyAttack())
                + CodedOutputStream.computeInt32Size(6, battle.maxRounds())
                + CodedOutputStream.computeByteArraySize(7, reward)
                + stringSize(8, battle.progressActivityId())
                + CodedOutputStream.computeInt32Size(9, battle.progressDelta())
                + CodedOutputStream.computeByteArraySize(10, encodeReward(battle.firstClearReward()))
                + CodedOutputStream.computeInt32Size(11, battle.sweepRequiredStars())
                + CodedOutputStream.computeInt32Size(12, battle.staminaCost());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, battle.stageId());
            output.writeInt32(2, battle.playerHp());
            output.writeInt32(3, battle.playerAttack());
            output.writeInt32(4, battle.enemyHp());
            output.writeInt32(5, battle.enemyAttack());
            output.writeInt32(6, battle.maxRounds());
            output.writeByteArray(7, reward);
            output.writeString(8, battle.progressActivityId());
            output.writeInt32(9, battle.progressDelta());
            output.writeByteArray(10, encodeReward(battle.firstClearReward()));
            output.writeInt32(11, battle.sweepRequiredStars());
            output.writeInt32(12, battle.staminaCost());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode battle stage definition", e);
        }
    }

    private static BattleStageDefinition decodeBattleStageDefinition(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String stageId = "";
        int playerHp = 1;
        int playerAttack = 1;
        int enemyHp = 1;
        int enemyAttack = 1;
        int maxRounds = 1;
        Reward reward = new Reward(List.of());
        String progressActivityId = "";
        int progressDelta = 0;
        Reward firstClearReward = Reward.of();
        int sweepRequiredStars = 0;
        int staminaCost = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> stageId = input.readString();
                    case 2 -> playerHp = input.readInt32();
                    case 3 -> playerAttack = input.readInt32();
                    case 4 -> enemyHp = input.readInt32();
                    case 5 -> enemyAttack = input.readInt32();
                    case 6 -> maxRounds = input.readInt32();
                    case 7 -> reward = decodeReward(input.readByteArray());
                    case 8 -> progressActivityId = input.readString();
                    case 9 -> progressDelta = input.readInt32();
                    case 10 -> firstClearReward = decodeReward(input.readByteArray());
                    case 11 -> sweepRequiredStars = input.readInt32();
                    case 12 -> staminaCost = input.readInt32();
                    default -> input.skipField(tag);
                }
            }
            return new BattleStageDefinition(stageId, playerHp, playerAttack, enemyHp, enemyAttack, maxRounds,
                    reward, progressActivityId, progressDelta, firstClearReward, sweepRequiredStars, staminaCost);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode battle stage definition", e);
        }
    }

    private static byte[] encodeTaskDefinition(TaskDefinition task) {
        byte[] reward = encodeReward(task.reward());
        int size = stringSize(1, task.taskId())
                + stringSize(2, task.progressRule().eventType())
                + stringSize(3, task.progressRule().subject())
                + CodedOutputStream.computeInt32Size(4, task.threshold())
                + CodedOutputStream.computeByteArraySize(5, reward);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, task.taskId());
            output.writeString(2, task.progressRule().eventType());
            output.writeString(3, task.progressRule().subject());
            output.writeInt32(4, task.threshold());
            output.writeByteArray(5, reward);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode task definition", e);
        }
    }

    private static TaskDefinition decodeTaskDefinition(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String taskId = "";
        String eventType = "";
        String subject = "";
        int threshold = 1;
        Reward reward = Reward.of();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> taskId = input.readString();
                    case 2 -> eventType = input.readString();
                    case 3 -> subject = input.readString();
                    case 4 -> threshold = input.readInt32();
                    case 5 -> reward = decodeReward(input.readByteArray());
                    default -> input.skipField(tag);
                }
            }
            return new TaskDefinition(taskId, eventType, subject, threshold, reward);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode task definition", e);
        }
    }

    private static byte[] encodeAchievementDefinition(AchievementDefinition achievement) {
        byte[] reward = encodeReward(achievement.reward());
        int size = stringSize(1, achievement.achievementId())
                + stringSize(2, achievement.progressRule().eventType())
                + stringSize(3, achievement.progressRule().subject())
                + CodedOutputStream.computeInt32Size(4, achievement.threshold())
                + CodedOutputStream.computeByteArraySize(5, reward);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, achievement.achievementId());
            output.writeString(2, achievement.progressRule().eventType());
            output.writeString(3, achievement.progressRule().subject());
            output.writeInt32(4, achievement.threshold());
            output.writeByteArray(5, reward);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode achievement definition", e);
        }
    }

    private static AchievementDefinition decodeAchievementDefinition(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String achievementId = "";
        String eventType = "";
        String subject = "";
        int threshold = 1;
        Reward reward = Reward.of();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> achievementId = input.readString();
                    case 2 -> eventType = input.readString();
                    case 3 -> subject = input.readString();
                    case 4 -> threshold = input.readInt32();
                    case 5 -> reward = decodeReward(input.readByteArray());
                    default -> input.skipField(tag);
                }
            }
            return new AchievementDefinition(achievementId, eventType, subject, threshold, reward);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode achievement definition", e);
        }
    }

    private static byte[] encodeEventProgressRule(EventProgressRule rule) {
        int size = stringSize(1, rule.eventType())
                + stringSize(2, rule.subject());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, rule.eventType());
            output.writeString(2, rule.subject());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode event progress rule", e);
        }
    }

    private static EventProgressRule decodeEventProgressRule(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String eventType = "";
        String subject = "";
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> eventType = input.readString();
                    case 2 -> subject = input.readString();
                    default -> input.skipField(tag);
                }
            }
            return new EventProgressRule(eventType, subject);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode event progress rule", e);
        }
    }

    private static byte[] encodeReward(Reward reward) {
        int size = 0;
        for (ItemStack item : reward.items()) {
            size += CodedOutputStream.computeByteArraySize(1, encodeItemStack(item));
        }
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            for (ItemStack item : reward.items()) {
                output.writeByteArray(1, encodeItemStack(item));
            }
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode reward", e);
        }
    }

    private static Reward decodeReward(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        List<ItemStack> items = new ArrayList<>();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> items.add(decodeItemStack(input.readByteArray()));
                    default -> input.skipField(tag);
                }
            }
            return new Reward(items);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode reward", e);
        }
    }

    private static byte[] encodeItemStack(ItemStack item) {
        int size = stringSize(1, item.itemId())
                + CodedOutputStream.computeInt32Size(2, item.count());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, item.itemId());
            output.writeInt32(2, item.count());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode item stack", e);
        }
    }

    private static ItemStack decodeItemStack(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String itemId = "";
        int count = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> itemId = input.readString();
                    case 2 -> count = input.readInt32();
                    default -> input.skipField(tag);
                }
            }
            return new ItemStack(itemId, count);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode item stack", e);
        }
    }

    private static byte[] encodeSchedule(ActivitySchedule schedule) {
        String type = "ALWAYS";
        long first = 0;
        long second = 0;
        if (schedule instanceof NaturalTimeSchedule natural) {
            type = "NATURAL";
            first = natural.startInclusive().toEpochMilli();
            second = natural.endExclusive().toEpochMilli();
        } else if (schedule instanceof OpenServerTimeSchedule openServer) {
            type = "OPEN_SERVER";
            first = openServer.startAfterOpen().toMillis();
            second = openServer.endAfterOpen().toMillis();
        } else if (!(schedule instanceof AlwaysOpenSchedule)) {
            throw new IllegalArgumentException("Unsupported activity schedule codec: " + schedule.getClass().getName());
        }
        int size = stringSize(1, type)
                + CodedOutputStream.computeInt64Size(2, first)
                + CodedOutputStream.computeInt64Size(3, second);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, type);
            output.writeInt64(2, first);
            output.writeInt64(3, second);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode activity schedule", e);
        }
    }

    private static ActivitySchedule decodeSchedule(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String type = "ALWAYS";
        long first = 0;
        long second = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> type = input.readString();
                    case 2 -> first = input.readInt64();
                    case 3 -> second = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return switch (type) {
                case "NATURAL" -> ActivitySchedule.naturalWindow(
                        Instant.ofEpochMilli(first),
                        Instant.ofEpochMilli(second)
                );
                case "OPEN_SERVER" -> ActivitySchedule.openServerWindow(
                        Duration.ofMillis(first),
                        Duration.ofMillis(second)
                );
                case "ALWAYS" -> ActivitySchedule.alwaysOpen();
                default -> throw new IllegalArgumentException("Unsupported activity schedule type: " + type);
            };
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode activity schedule", e);
        }
    }

    private static byte[] encodeParticipation(ParticipationCondition participation) {
        String type = "ALWAYS";
        int value = 0;
        if (participation instanceof MinLevelCondition minLevel) {
            type = "MIN_LEVEL";
            value = minLevel.level();
        } else if (!(participation instanceof AlwaysParticipationCondition)) {
            throw new IllegalArgumentException("Unsupported participation codec: " + participation.getClass().getName());
        }
        int size = stringSize(1, type)
                + CodedOutputStream.computeInt32Size(2, value);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, type);
            output.writeInt32(2, value);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode participation", e);
        }
    }

    private static ParticipationCondition decodeParticipation(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String type = "ALWAYS";
        int value = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> type = input.readString();
                    case 2 -> value = input.readInt32();
                    default -> input.skipField(tag);
                }
            }
            if ("MIN_LEVEL".equals(type)) {
                return ParticipationCondition.minLevel(value);
            }
            if ("ALWAYS".equals(type)) {
                return ParticipationCondition.always();
            }
            throw new IllegalArgumentException("Unsupported participation type: " + type);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode participation", e);
        }
    }

    private static byte[] encodeGrowth(GrowthTuning growth) {
        int size = stringSize(1, growth.expItemId())
                + CodedOutputStream.computeInt32Size(2, growth.expPerItem())
                + CodedOutputStream.computeInt32Size(3, growth.expPerLevel());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, growth.expItemId());
            output.writeInt32(2, growth.expPerItem());
            output.writeInt32(3, growth.expPerLevel());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode growth config", e);
        }
    }

    private static GrowthTuning decodeGrowth(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String expItemId = "";
        int expPerItem = 0;
        int expPerLevel = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> expItemId = input.readString();
                    case 2 -> expPerItem = input.readInt32();
                    case 3 -> expPerLevel = input.readInt32();
                    default -> input.skipField(tag);
                }
            }
            return new GrowthTuning(expItemId, expPerItem, expPerLevel);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode growth config", e);
        }
    }

    private static byte[] encodeSubscription(ServiceId subscriber, String topic, Set<String> ownerKeys, Duration leaseTtl) {
        int size = stringSize(1, subscriber.kind().name())
                + stringSize(2, subscriber.region())
                + stringSize(3, subscriber.node())
                + stringSize(4, topic);
        for (String ownerKey : ownerKeys) {
            size += stringSize(5, ownerKey);
        }
        long leaseMillis = leaseTtl.toMillis();
        if (leaseMillis > 0) {
            size += CodedOutputStream.computeInt64Size(6, leaseMillis);
        }
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, subscriber.kind().name());
            output.writeString(2, subscriber.region());
            output.writeString(3, subscriber.node());
            output.writeString(4, topic);
            for (String ownerKey : ownerKeys) {
                output.writeString(5, ownerKey);
            }
            if (leaseMillis > 0) {
                output.writeInt64(6, leaseMillis);
            }
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
        Set<String> ownerKeys = new HashSet<>();
        long leaseMillis = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> kind = input.readString();
                    case 2 -> region = input.readString();
                    case 3 -> node = input.readString();
                    case 4 -> topic = input.readString();
                    case 5 -> ownerKeys.add(input.readString());
                    case 6 -> leaseMillis = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return new Subscription(
                    ServiceId.of(ServiceKind.valueOf(kind), region, node),
                    topic,
                    ownerKeys,
                    Duration.ofMillis(leaseMillis)
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode event subscription", e);
        }
    }

    private static byte[] encodeKnownRevision(String ownerKey, long revision) {
        int size = stringSize(1, ownerKey)
                + CodedOutputStream.computeInt64Size(2, revision);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, ownerKey);
            output.writeInt64(2, revision);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode event known revision", e);
        }
    }

    private static KnownRevision decodeKnownRevision(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String ownerKey = "";
        long revision = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> ownerKey = input.readString();
                    case 2 -> revision = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return new KnownRevision(ownerKey, revision);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode event known revision", e);
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

    private static byte[] encodeAllianceSnapshot(AllianceSnapshot snapshot) {
        int size = CodedOutputStream.computeInt64Size(1, snapshot.allianceId())
                + CodedOutputStream.computeInt64Size(2, snapshot.revision());
        for (long playerId : snapshot.members()) {
            size += CodedOutputStream.computeInt64Size(3, playerId);
        }
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, snapshot.allianceId());
            output.writeInt64(2, snapshot.revision());
            for (long playerId : snapshot.members()) {
                output.writeInt64(3, playerId);
            }
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode alliance snapshot", e);
        }
    }

    private static AllianceSnapshot decodeAllianceSnapshot(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long allianceId = 0;
        long revision = 0;
        Set<Long> members = new HashSet<>();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> allianceId = input.readInt64();
                    case 2 -> revision = input.readInt64();
                    case 3 -> members.add(input.readInt64());
                    default -> input.skipField(tag);
                }
            }
            return new AllianceSnapshot(allianceId, revision, members);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode alliance snapshot", e);
        }
    }

    private static byte[] encodeFriendSnapshot(FriendSnapshot snapshot) {
        int size = CodedOutputStream.computeInt64Size(1, snapshot.playerId())
                + CodedOutputStream.computeInt64Size(2, snapshot.revision());
        for (long friendId : snapshot.friends()) {
            size += CodedOutputStream.computeInt64Size(3, friendId);
        }
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, snapshot.playerId());
            output.writeInt64(2, snapshot.revision());
            for (long friendId : snapshot.friends()) {
                output.writeInt64(3, friendId);
            }
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode friend snapshot", e);
        }
    }

    private static FriendSnapshot decodeFriendSnapshot(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long playerId = 0;
        long revision = 0;
        Set<Long> friends = new HashSet<>();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> playerId = input.readInt64();
                    case 2 -> revision = input.readInt64();
                    case 3 -> friends.add(input.readInt64());
                    default -> input.skipField(tag);
                }
            }
            return new FriendSnapshot(playerId, revision, friends);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode friend snapshot", e);
        }
    }

    private static byte[] encodePlayerDomainProjectionSnapshot(PlayerDomainProjectionSnapshot snapshot) {
        List<byte[]> stageEntries = new ArrayList<>();
        int size = CodedOutputStream.computeInt64Size(1, snapshot.playerId())
                + CodedOutputStream.computeInt64Size(2, snapshot.eventRevision());
        for (Map.Entry<String, Integer> entry : snapshot.stageClearCounts().entrySet()) {
            byte[] encoded = encodeStageClearEntry(entry.getKey(), entry.getValue());
            stageEntries.add(encoded);
            size += CodedOutputStream.computeByteArraySize(3, encoded);
        }
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, snapshot.playerId());
            output.writeInt64(2, snapshot.eventRevision());
            for (byte[] entry : stageEntries) {
                output.writeByteArray(3, entry);
            }
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player domain projection snapshot", e);
        }
    }

    private static PlayerDomainProjectionSnapshot decodePlayerDomainProjectionSnapshot(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long playerId = 1;
        long eventRevision = 0;
        Map<String, Integer> stageClears = new HashMap<>();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> playerId = input.readInt64();
                    case 2 -> eventRevision = input.readInt64();
                    case 3 -> decodeStageClearEntry(input.readByteArray(), stageClears);
                    default -> input.skipField(tag);
                }
            }
            return new PlayerDomainProjectionSnapshot(playerId, eventRevision, stageClears);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player domain projection snapshot", e);
        }
    }

    private static byte[] encodeStageClearEntry(String stageId, int clearCount) {
        int size = stringSize(1, stageId)
                + CodedOutputStream.computeInt32Size(2, clearCount);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, stageId);
            output.writeInt32(2, clearCount);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player domain stage clear entry", e);
        }
    }

    private static void decodeStageClearEntry(byte[] bytes, Map<String, Integer> stageClears) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String stageId = "";
        int clearCount = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> stageId = input.readString();
                    case 2 -> clearCount = input.readInt32();
                    default -> input.skipField(tag);
                }
            }
            if (!stageId.isBlank()) {
                stageClears.put(stageId, clearCount);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player domain stage clear entry", e);
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
