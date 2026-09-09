package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.RegistryEvent;
import com.commonbattle.cluster.RegistryEventType;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.protocol.PayloadCodec;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 注册中心协议 payload codec。
 */
public final class RegistryPayloadCodecs {
    private RegistryPayloadCodecs() {
    }

    public static PayloadCodecRegistry registerTo(PayloadCodecRegistry registry) {
        registry.register(new RegisterRequestCodec());
        registry.register(new HeartbeatRequestCodec());
        registry.register(new UnregisterRequestCodec());
        registry.register(new ListRequestCodec());
        registry.register(new ListResponseCodec());
        registry.register(new SubscribeRequestCodec());
        registry.register(new UnsubscribeRequestCodec());
        registry.register(new ReplayRequestCodec());
        registry.register(new ReplayResponseCodec());
        registry.register(new RegistryEventCodec());
        registry.register(new AckCodec());
        return registry;
    }

    private static byte[] stringBytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String string(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static byte[] encodeServiceId(ServiceId id) {
        return stringBytes(id.kind().name() + "|" + id.region() + "|" + id.node());
    }

    static ServiceId decodeServiceId(byte[] bytes) {
        String[] parts = string(bytes).split("\\|", -1);
        return ServiceId.of(ServiceKind.valueOf(parts[0]), parts[1], parts[2]);
    }

    static byte[] encodeDescriptor(ServiceDescriptor service) {
        String payload = service.id().kind().name()
                + "|" + service.id().region()
                + "|" + service.id().node()
                + "|" + service.endpoint().host()
                + "|" + service.endpoint().port()
                + "|" + String.join(",", service.topics())
                + "|" + encodeMap(service.metadata());
        return stringBytes(payload);
    }

    static ServiceDescriptor decodeDescriptor(byte[] bytes) {
        String[] parts = string(bytes).split("\\|", -1);
        Set<String> topics = parts[5].isBlank() ? Set.of() : Set.of(parts[5].split(","));
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.valueOf(parts[0]), parts[1], parts[2]),
                new ServiceEndpoint(parts[3], Integer.parseInt(parts[4])),
                topics,
                decodeMap(parts.length > 6 ? parts[6] : "")
        );
    }

    private static String encodeMap(Map<String, String> map) {
        List<String> pairs = new ArrayList<>();
        map.forEach((key, value) -> pairs.add(key + "=" + value));
        return String.join(",", pairs);
    }

    private static Map<String, String> decodeMap(String value) {
        if (value.isBlank()) {
            return Map.of();
        }
        java.util.HashMap<String, String> result = new java.util.HashMap<>();
        for (String pair : value.split(",")) {
            String[] parts = pair.split("=", 2);
            result.put(parts[0], parts.length > 1 ? parts[1] : "");
        }
        return result;
    }

    private static final class RegisterRequestCodec implements PayloadCodec<RegistryRegisterRequest> {
        @Override
        public String typeName() {
            return RegistryRegisterRequest.class.getName();
        }

        @Override
        public Class<RegistryRegisterRequest> javaType() {
            return RegistryRegisterRequest.class;
        }

        @Override
        public byte[] encode(RegistryRegisterRequest payload) {
            byte[] service = encodeDescriptor(payload.service());
            long leaseMillis = payload.leaseTtl().toMillis();
            int size = CodedOutputStream.computeByteArraySize(1, service);
            if (leaseMillis > 0) {
                size += CodedOutputStream.computeInt64Size(2, leaseMillis);
            }
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeByteArray(1, service);
                if (leaseMillis > 0) {
                    output.writeInt64(2, leaseMillis);
                }
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode registry register request", e);
            }
        }

        @Override
        public RegistryRegisterRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            ServiceDescriptor service = null;
            long leaseMillis = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    int field = WireFormat.getTagFieldNumber(tag);
                    if (field == 1) {
                        service = decodeDescriptor(input.readByteArray());
                    } else if (field == 2) {
                        leaseMillis = input.readInt64();
                    } else {
                        input.skipField(tag);
                    }
                }
                if (service == null) {
                    throw new IllegalStateException("Missing service in registry register request");
                }
                return new RegistryRegisterRequest(service, Duration.ofMillis(leaseMillis));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode registry register request", e);
            }
        }
    }

    private static final class HeartbeatRequestCodec implements PayloadCodec<RegistryHeartbeatRequest> {
        @Override
        public String typeName() {
            return RegistryHeartbeatRequest.class.getName();
        }

        @Override
        public Class<RegistryHeartbeatRequest> javaType() {
            return RegistryHeartbeatRequest.class;
        }

        @Override
        public byte[] encode(RegistryHeartbeatRequest payload) {
            byte[] serviceId = encodeServiceId(payload.serviceId());
            long leaseMillis = payload.leaseTtl().toMillis();
            int size = CodedOutputStream.computeByteArraySize(1, serviceId)
                    + CodedOutputStream.computeInt64Size(2, leaseMillis);
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeByteArray(1, serviceId);
                output.writeInt64(2, leaseMillis);
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode registry heartbeat request", e);
            }
        }

        @Override
        public RegistryHeartbeatRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            ServiceId serviceId = null;
            long leaseMillis = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    int field = WireFormat.getTagFieldNumber(tag);
                    if (field == 1) {
                        serviceId = decodeServiceId(input.readByteArray());
                    } else if (field == 2) {
                        leaseMillis = input.readInt64();
                    } else {
                        input.skipField(tag);
                    }
                }
                if (serviceId == null) {
                    throw new IllegalStateException("Missing service id in registry heartbeat request");
                }
                return new RegistryHeartbeatRequest(serviceId, Duration.ofMillis(leaseMillis));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode registry heartbeat request", e);
            }
        }
    }

    private static final class UnregisterRequestCodec implements PayloadCodec<RegistryUnregisterRequest> {
        @Override
        public String typeName() {
            return RegistryUnregisterRequest.class.getName();
        }

        @Override
        public Class<RegistryUnregisterRequest> javaType() {
            return RegistryUnregisterRequest.class;
        }

        @Override
        public byte[] encode(RegistryUnregisterRequest payload) {
            return encodeServiceId(payload.serviceId());
        }

        @Override
        public RegistryUnregisterRequest decode(byte[] bytes) {
            return new RegistryUnregisterRequest(decodeServiceId(bytes));
        }
    }

    private static final class ListRequestCodec implements PayloadCodec<RegistryListRequest> {
        @Override
        public String typeName() {
            return RegistryListRequest.class.getName();
        }

        @Override
        public Class<RegistryListRequest> javaType() {
            return RegistryListRequest.class;
        }

        @Override
        public byte[] encode(RegistryListRequest payload) {
            return stringBytes(payload.kind().name());
        }

        @Override
        public RegistryListRequest decode(byte[] bytes) {
            return new RegistryListRequest(ServiceKind.valueOf(string(bytes)));
        }
    }

    private static final class ListResponseCodec implements PayloadCodec<RegistryListResponse> {
        @Override
        public String typeName() {
            return RegistryListResponse.class.getName();
        }

        @Override
        public Class<RegistryListResponse> javaType() {
            return RegistryListResponse.class;
        }

        @Override
        public byte[] encode(RegistryListResponse payload) {
            int size = 0;
            List<byte[]> services = payload.services().stream().map(RegistryPayloadCodecs::encodeDescriptor).toList();
            for (byte[] service : services) {
                size += CodedOutputStream.computeByteArraySize(1, service);
            }
            if (payload.version() > 0) {
                size += CodedOutputStream.computeInt64Size(2, payload.version());
            }
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                for (byte[] service : services) {
                    output.writeByteArray(1, service);
                }
                if (payload.version() > 0) {
                    output.writeInt64(2, payload.version());
                }
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode registry list response", e);
            }
        }

        @Override
        public RegistryListResponse decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            List<ServiceDescriptor> services = new ArrayList<>();
            long version = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> services.add(decodeDescriptor(input.readByteArray()));
                        case 2 -> version = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                return new RegistryListResponse(services, version);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode registry list response", e);
            }
        }
    }

    private static final class SubscribeRequestCodec implements PayloadCodec<RegistrySubscribeRequest> {
        @Override
        public String typeName() {
            return RegistrySubscribeRequest.class.getName();
        }

        @Override
        public Class<RegistrySubscribeRequest> javaType() {
            return RegistrySubscribeRequest.class;
        }

        @Override
        public byte[] encode(RegistrySubscribeRequest payload) {
            byte[] subscriber = encodeServiceId(payload.subscriber());
            int size = CodedOutputStream.computeByteArraySize(1, subscriber)
                    + CodedOutputStream.computeStringSize(2, payload.kind().name());
            if (payload.sinceVersion() > 0) {
                size += CodedOutputStream.computeInt64Size(3, payload.sinceVersion());
            }
            long leaseMillis = payload.leaseTtl().toMillis();
            if (leaseMillis > 0) {
                size += CodedOutputStream.computeInt64Size(4, leaseMillis);
            }
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeByteArray(1, subscriber);
                output.writeString(2, payload.kind().name());
                if (payload.sinceVersion() > 0) {
                    output.writeInt64(3, payload.sinceVersion());
                }
                if (leaseMillis > 0) {
                    output.writeInt64(4, leaseMillis);
                }
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode registry subscribe request", e);
            }
        }

        @Override
        public RegistrySubscribeRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            ServiceId subscriber = null;
            ServiceKind kind = null;
            long sinceVersion = 0;
            long leaseMillis = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> subscriber = decodeServiceId(input.readByteArray());
                        case 2 -> kind = ServiceKind.valueOf(input.readString());
                        case 3 -> sinceVersion = input.readInt64();
                        case 4 -> leaseMillis = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                if (subscriber == null || kind == null) {
                    throw new IllegalStateException("Missing subscriber or kind in registry subscribe request");
                }
                return new RegistrySubscribeRequest(subscriber, kind, sinceVersion, Duration.ofMillis(leaseMillis));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode registry subscribe request", e);
            }
        }
    }

    private static final class UnsubscribeRequestCodec implements PayloadCodec<RegistryUnsubscribeRequest> {
        @Override
        public String typeName() {
            return RegistryUnsubscribeRequest.class.getName();
        }

        @Override
        public Class<RegistryUnsubscribeRequest> javaType() {
            return RegistryUnsubscribeRequest.class;
        }

        @Override
        public byte[] encode(RegistryUnsubscribeRequest payload) {
            byte[] subscriber = encodeServiceId(payload.subscriber());
            int size = CodedOutputStream.computeByteArraySize(1, subscriber)
                    + CodedOutputStream.computeStringSize(2, payload.kind().name());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeByteArray(1, subscriber);
                output.writeString(2, payload.kind().name());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode registry unsubscribe request", e);
            }
        }

        @Override
        public RegistryUnsubscribeRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            ServiceId subscriber = null;
            ServiceKind kind = null;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> subscriber = decodeServiceId(input.readByteArray());
                        case 2 -> kind = ServiceKind.valueOf(input.readString());
                        default -> input.skipField(tag);
                    }
                }
                if (subscriber == null || kind == null) {
                    throw new IllegalStateException("Missing subscriber or kind in registry unsubscribe request");
                }
                return new RegistryUnsubscribeRequest(subscriber, kind);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode registry unsubscribe request", e);
            }
        }
    }

    private static final class ReplayRequestCodec implements PayloadCodec<RegistryReplayRequest> {
        @Override
        public String typeName() {
            return RegistryReplayRequest.class.getName();
        }

        @Override
        public Class<RegistryReplayRequest> javaType() {
            return RegistryReplayRequest.class;
        }

        @Override
        public byte[] encode(RegistryReplayRequest payload) {
            byte[] subscriber = encodeServiceId(payload.subscriber());
            int size = CodedOutputStream.computeByteArraySize(1, subscriber)
                    + CodedOutputStream.computeStringSize(2, payload.kind().name())
                    + CodedOutputStream.computeInt64Size(3, payload.sinceVersion());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeByteArray(1, subscriber);
                output.writeString(2, payload.kind().name());
                output.writeInt64(3, payload.sinceVersion());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode registry replay request", e);
            }
        }

        @Override
        public RegistryReplayRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            ServiceId subscriber = null;
            ServiceKind kind = null;
            long sinceVersion = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> subscriber = decodeServiceId(input.readByteArray());
                        case 2 -> kind = ServiceKind.valueOf(input.readString());
                        case 3 -> sinceVersion = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                if (subscriber == null || kind == null) {
                    throw new IllegalStateException("Missing subscriber or kind in registry replay request");
                }
                return new RegistryReplayRequest(subscriber, kind, sinceVersion);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode registry replay request", e);
            }
        }
    }

    private static final class ReplayResponseCodec implements PayloadCodec<RegistryReplayResponse> {
        @Override
        public String typeName() {
            return RegistryReplayResponse.class.getName();
        }

        @Override
        public Class<RegistryReplayResponse> javaType() {
            return RegistryReplayResponse.class;
        }

        @Override
        public byte[] encode(RegistryReplayResponse payload) {
            List<byte[]> events = payload.events().stream().map(RegistryPayloadCodecs::encodeEvent).toList();
            int size = CodedOutputStream.computeInt64Size(2, payload.currentVersion())
                    + CodedOutputStream.computeBoolSize(3, payload.compacted());
            if (payload.minReplayVersion() > 0) {
                size += CodedOutputStream.computeInt64Size(4, payload.minReplayVersion());
            }
            for (byte[] event : events) {
                size += CodedOutputStream.computeByteArraySize(1, event);
            }
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                for (byte[] event : events) {
                    output.writeByteArray(1, event);
                }
                output.writeInt64(2, payload.currentVersion());
                output.writeBool(3, payload.compacted());
                if (payload.minReplayVersion() > 0) {
                    output.writeInt64(4, payload.minReplayVersion());
                }
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode registry replay response", e);
            }
        }

        @Override
        public RegistryReplayResponse decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            List<RegistryEvent> events = new ArrayList<>();
            long currentVersion = 0;
            boolean compacted = false;
            long minReplayVersion = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> events.add(decodeEvent(input.readByteArray()));
                        case 2 -> currentVersion = input.readInt64();
                        case 3 -> compacted = input.readBool();
                        case 4 -> minReplayVersion = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                return new RegistryReplayResponse(events, currentVersion, compacted, minReplayVersion);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode registry replay response", e);
            }
        }
    }

    private static final class RegistryEventCodec implements PayloadCodec<RegistryEvent> {
        @Override
        public String typeName() {
            return RegistryEvent.class.getName();
        }

        @Override
        public Class<RegistryEvent> javaType() {
            return RegistryEvent.class;
        }

        @Override
        public byte[] encode(RegistryEvent payload) {
            return encodeEvent(payload);
        }

        @Override
        public RegistryEvent decode(byte[] bytes) {
            return decodeEvent(bytes);
        }
    }

    private static byte[] encodeEvent(RegistryEvent event) {
        byte[] service = encodeDescriptor(event.service());
        int size = CodedOutputStream.computeStringSize(1, event.type().name())
                + CodedOutputStream.computeByteArraySize(2, service);
        if (event.version() > 0) {
            size += CodedOutputStream.computeInt64Size(3, event.version());
        }
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, event.type().name());
            output.writeByteArray(2, service);
            if (event.version() > 0) {
                output.writeInt64(3, event.version());
            }
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode registry event", e);
        }
    }

    private static RegistryEvent decodeEvent(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        RegistryEventType type = null;
        ServiceDescriptor service = null;
        long version = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> type = RegistryEventType.valueOf(input.readString());
                    case 2 -> service = decodeDescriptor(input.readByteArray());
                    case 3 -> version = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            if (type == null || service == null) {
                throw new IllegalStateException("Missing type or service in registry event");
            }
            return new RegistryEvent(type, service, version);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode registry event", e);
        }
    }

    private static final class AckCodec implements PayloadCodec<RegistryAck> {
        @Override
        public String typeName() {
            return RegistryAck.class.getName();
        }

        @Override
        public Class<RegistryAck> javaType() {
            return RegistryAck.class;
        }

        @Override
        public byte[] encode(RegistryAck payload) {
            return stringBytes(payload.message());
        }

        @Override
        public RegistryAck decode(byte[] bytes) {
            return new RegistryAck(string(bytes));
        }
    }
}
