package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.cluster.protocol.PayloadCodec;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;

/**
 * Agent 迁移 RPC payload codec。
 */
public final class AgentMigrationPayloadCodecs {
    private AgentMigrationPayloadCodecs() {
    }

    public static PayloadCodecRegistry registerTo(PayloadCodecRegistry registry) {
        registry.register(new AcceptRequestCodec());
        registry.register(new AcceptResponseCodec());
        return registry;
    }

    private static byte[] identity(AgentIdentity identity) {
        int size = CodedOutputStream.computeStringSize(1, identity.type())
                + CodedOutputStream.computeStringSize(2, identity.key());
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

    private static final class AcceptRequestCodec implements PayloadCodec<AgentMigrationAcceptRequest> {
        @Override
        public String typeName() {
            return AgentMigrationAcceptRequest.class.getName();
        }

        @Override
        public Class<AgentMigrationAcceptRequest> javaType() {
            return AgentMigrationAcceptRequest.class;
        }

        @Override
        public byte[] encode(AgentMigrationAcceptRequest payload) {
            byte[] identity = identity(payload.identity());
            byte[] stateBytes = payload.stateBytes();
            int size = CodedOutputStream.computeByteArraySize(1, identity)
                    + CodedOutputStream.computeStringSize(2, payload.actorId())
                    + CodedOutputStream.computeStringSize(3, payload.stateType())
                    + CodedOutputStream.computeByteArraySize(4, stateBytes)
                    + CodedOutputStream.computeStringSize(5, payload.taskId());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeByteArray(1, identity);
                output.writeString(2, payload.actorId());
                output.writeString(3, payload.stateType());
                output.writeByteArray(4, stateBytes);
                output.writeString(5, payload.taskId());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode agent migration accept request", e);
            }
        }

        @Override
        public AgentMigrationAcceptRequest decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            byte[] identity = new byte[0];
            String actorId = "";
            String stateType = "";
            byte[] stateBytes = new byte[0];
            String taskId = "";
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> identity = input.readByteArray();
                        case 2 -> actorId = input.readString();
                        case 3 -> stateType = input.readString();
                        case 4 -> stateBytes = input.readByteArray();
                        case 5 -> taskId = input.readString();
                        default -> input.skipField(tag);
                    }
                }
                return new AgentMigrationAcceptRequest(taskId, identity(identity), actorId, stateType, stateBytes);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode agent migration accept request", e);
            }
        }
    }

    private static final class AcceptResponseCodec implements PayloadCodec<AgentMigrationAcceptResponse> {
        @Override
        public String typeName() {
            return AgentMigrationAcceptResponse.class.getName();
        }

        @Override
        public Class<AgentMigrationAcceptResponse> javaType() {
            return AgentMigrationAcceptResponse.class;
        }

        @Override
        public byte[] encode(AgentMigrationAcceptResponse payload) {
            int size = CodedOutputStream.computeBoolSize(1, payload.accepted())
                    + CodedOutputStream.computeStringSize(2, payload.reason());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeBool(1, payload.accepted());
                output.writeString(2, payload.reason());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode agent migration accept response", e);
            }
        }

        @Override
        public AgentMigrationAcceptResponse decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            boolean accepted = false;
            String reason = "";
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> accepted = input.readBool();
                        case 2 -> reason = input.readString();
                        default -> input.skipField(tag);
                    }
                }
                return new AgentMigrationAcceptResponse(accepted, reason);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode agent migration accept response", e);
            }
        }
    }
}
