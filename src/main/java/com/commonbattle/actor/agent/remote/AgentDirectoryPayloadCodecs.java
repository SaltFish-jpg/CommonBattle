package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.protocol.PayloadCodec;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;

/**
 * AgentDirectory RPC payload codec。
 */
public final class AgentDirectoryPayloadCodecs {
    private AgentDirectoryPayloadCodecs() {
    }

    public static PayloadCodecRegistry registerTo(PayloadCodecRegistry registry) {
        registry.register(new ClaimRequestCodec());
        registry.register(new MoveRequestCodec());
        registry.register(new UnbindRequestCodec());
        registry.register(new LocateRequestCodec());
        registry.register(new BooleanResponseCodec());
        registry.register(new LocateResponseCodec());
        return registry;
    }

    private static byte[] identity(AgentIdentity identity) {
        int size = stringSize(1, identity.type()) + stringSize(2, identity.key());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, identity.type());
            output.writeString(2, identity.key());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode agent identity", e);
        }
    }

    private static AgentIdentity identity(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String type = "";
        String key = "";
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> type = input.readString();
                    case 2 -> key = input.readString();
                    default -> input.skipField(tag);
                }
            }
            return new AgentIdentity(type, key);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode agent identity", e);
        }
    }

    private static byte[] location(AgentLocation location) {
        int size = stringSize(1, location.serviceId().kind().name())
                + stringSize(2, location.serviceId().region())
                + stringSize(3, location.serviceId().node())
                + stringSize(4, location.actorRef().id());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, location.serviceId().kind().name());
            output.writeString(2, location.serviceId().region());
            output.writeString(3, location.serviceId().node());
            output.writeString(4, location.actorRef().id());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode agent location", e);
        }
    }

    private static AgentLocation location(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String kind = "";
        String region = "";
        String node = "";
        String actorId = "";
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> kind = input.readString();
                    case 2 -> region = input.readString();
                    case 3 -> node = input.readString();
                    case 4 -> actorId = input.readString();
                    default -> input.skipField(tag);
                }
            }
            return new AgentLocation(ServiceId.of(ServiceKind.valueOf(kind), region, node), new ActorRef(actorId));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode agent location", e);
        }
    }

    private static int stringSize(int fieldNumber, String value) {
        return CodedOutputStream.computeStringSize(fieldNumber, value);
    }

    private static final class ClaimRequestCodec implements PayloadCodec<AgentDirectoryClaimRequest> {
        @Override
        public String typeName() {
            return AgentDirectoryClaimRequest.class.getName();
        }

        @Override
        public Class<AgentDirectoryClaimRequest> javaType() {
            return AgentDirectoryClaimRequest.class;
        }

        @Override
        public byte[] encode(AgentDirectoryClaimRequest payload) {
            return twoBytes(identity(payload.identity()), location(payload.location()));
        }

        @Override
        public AgentDirectoryClaimRequest decode(byte[] bytes) {
            BytePair pair = twoBytes(bytes);
            return new AgentDirectoryClaimRequest(identity(pair.first()), location(pair.second()));
        }
    }

    private static final class MoveRequestCodec implements PayloadCodec<AgentDirectoryMoveRequest> {
        @Override
        public String typeName() {
            return AgentDirectoryMoveRequest.class.getName();
        }

        @Override
        public Class<AgentDirectoryMoveRequest> javaType() {
            return AgentDirectoryMoveRequest.class;
        }

        @Override
        public byte[] encode(AgentDirectoryMoveRequest payload) {
            byte[] identity = identity(payload.identity());
            byte[] expected = location(payload.expectedCurrent());
            byte[] next = location(payload.next());
            int size = CodedOutputStream.computeByteArraySize(1, identity)
                    + CodedOutputStream.computeByteArraySize(2, expected)
                    + CodedOutputStream.computeByteArraySize(3, next);
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeByteArray(1, identity);
                output.writeByteArray(2, expected);
                output.writeByteArray(3, next);
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode agent directory move request", e);
            }
        }

        @Override
        public AgentDirectoryMoveRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            byte[] identity = new byte[0];
            byte[] expected = new byte[0];
            byte[] next = new byte[0];
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> identity = input.readByteArray();
                        case 2 -> expected = input.readByteArray();
                        case 3 -> next = input.readByteArray();
                        default -> input.skipField(tag);
                    }
                }
                return new AgentDirectoryMoveRequest(identity(identity), location(expected), location(next));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode agent directory move request", e);
            }
        }
    }

    private static final class UnbindRequestCodec implements PayloadCodec<AgentDirectoryUnbindRequest> {
        @Override
        public String typeName() {
            return AgentDirectoryUnbindRequest.class.getName();
        }

        @Override
        public Class<AgentDirectoryUnbindRequest> javaType() {
            return AgentDirectoryUnbindRequest.class;
        }

        @Override
        public byte[] encode(AgentDirectoryUnbindRequest payload) {
            return twoBytes(identity(payload.identity()), location(payload.location()));
        }

        @Override
        public AgentDirectoryUnbindRequest decode(byte[] bytes) {
            BytePair pair = twoBytes(bytes);
            return new AgentDirectoryUnbindRequest(identity(pair.first()), location(pair.second()));
        }
    }

    private static final class LocateRequestCodec implements PayloadCodec<AgentDirectoryLocateRequest> {
        @Override
        public String typeName() {
            return AgentDirectoryLocateRequest.class.getName();
        }

        @Override
        public Class<AgentDirectoryLocateRequest> javaType() {
            return AgentDirectoryLocateRequest.class;
        }

        @Override
        public byte[] encode(AgentDirectoryLocateRequest payload) {
            return identity(payload.identity());
        }

        @Override
        public AgentDirectoryLocateRequest decode(byte[] bytes) {
            return new AgentDirectoryLocateRequest(identity(bytes));
        }
    }

    private static final class BooleanResponseCodec implements PayloadCodec<AgentDirectoryBooleanResponse> {
        @Override
        public String typeName() {
            return AgentDirectoryBooleanResponse.class.getName();
        }

        @Override
        public Class<AgentDirectoryBooleanResponse> javaType() {
            return AgentDirectoryBooleanResponse.class;
        }

        @Override
        public byte[] encode(AgentDirectoryBooleanResponse payload) {
            byte[] bytes = new byte[CodedOutputStream.computeBoolSize(1, payload.accepted())];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeBool(1, payload.accepted());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode agent directory boolean response", e);
            }
        }

        @Override
        public AgentDirectoryBooleanResponse decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            boolean accepted = false;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    if (WireFormat.getTagFieldNumber(tag) == 1) {
                        accepted = input.readBool();
                    } else {
                        input.skipField(tag);
                    }
                }
                return new AgentDirectoryBooleanResponse(accepted);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode agent directory boolean response", e);
            }
        }
    }

    private static final class LocateResponseCodec implements PayloadCodec<AgentDirectoryLocateResponse> {
        @Override
        public String typeName() {
            return AgentDirectoryLocateResponse.class.getName();
        }

        @Override
        public Class<AgentDirectoryLocateResponse> javaType() {
            return AgentDirectoryLocateResponse.class;
        }

        @Override
        public byte[] encode(AgentDirectoryLocateResponse payload) {
            if (payload.location() == null) {
                return new byte[0];
            }
            byte[] location = location(payload.location());
            byte[] bytes = new byte[CodedOutputStream.computeByteArraySize(1, location)];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeByteArray(1, location);
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode agent directory locate response", e);
            }
        }

        @Override
        public AgentDirectoryLocateResponse decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            AgentLocation location = null;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    if (WireFormat.getTagFieldNumber(tag) == 1) {
                        location = location(input.readByteArray());
                    } else {
                        input.skipField(tag);
                    }
                }
                return new AgentDirectoryLocateResponse(location);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode agent directory locate response", e);
            }
        }
    }

    private static byte[] twoBytes(byte[] first, byte[] second) {
        int size = CodedOutputStream.computeByteArraySize(1, first)
                + CodedOutputStream.computeByteArraySize(2, second);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeByteArray(1, first);
            output.writeByteArray(2, second);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode agent directory payload", e);
        }
    }

    private static BytePair twoBytes(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        byte[] first = new byte[0];
        byte[] second = new byte[0];
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> first = input.readByteArray();
                    case 2 -> second = input.readByteArray();
                    default -> input.skipField(tag);
                }
            }
            return new BytePair(first, second);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode agent directory payload", e);
        }
    }

    private record BytePair(byte[] first, byte[] second) {
    }
}
