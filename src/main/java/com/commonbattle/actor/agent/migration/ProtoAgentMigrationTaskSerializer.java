package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.time.Instant;

/**
 * 基于 protobuf wire format 的 Agent 迁移任务序列化器。
 * 字段号只允许尾部追加，历史字段删除后必须保留字段号，便于迁移恢复日志跨版本解码。
 */
public final class ProtoAgentMigrationTaskSerializer implements AgentMigrationTaskSerializer {
    @Override
    public byte[] encode(AgentMigrationTask task) {
        byte[] identity = encodeIdentity(task.identity());
        byte[] source = encodeLocation(task.source());
        byte[] target = encodeLocation(task.target());
        byte[] snapshot = encodeSnapshot(task.snapshot());
        int size = CodedOutputStream.computeStringSize(1, task.taskId())
                + CodedOutputStream.computeByteArraySize(2, identity)
                + CodedOutputStream.computeByteArraySize(3, source)
                + CodedOutputStream.computeByteArraySize(4, target)
                + CodedOutputStream.computeByteArraySize(5, snapshot)
                + CodedOutputStream.computeStringSize(6, task.status().name())
                + CodedOutputStream.computeStringSize(7, task.reason())
                + CodedOutputStream.computeInt64Size(8, task.updatedAt().toEpochMilli())
                + CodedOutputStream.computeStringSize(9, task.leaseOwner())
                + CodedOutputStream.computeInt64Size(10, task.leaseExpiresAt().toEpochMilli());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, task.taskId());
            output.writeByteArray(2, identity);
            output.writeByteArray(3, source);
            output.writeByteArray(4, target);
            output.writeByteArray(5, snapshot);
            output.writeString(6, task.status().name());
            output.writeString(7, task.reason());
            output.writeInt64(8, task.updatedAt().toEpochMilli());
            output.writeString(9, task.leaseOwner());
            output.writeInt64(10, task.leaseExpiresAt().toEpochMilli());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode agent migration task", e);
        }
    }

    @Override
    public AgentMigrationTask decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String taskId = "";
        byte[] identity = new byte[0];
        byte[] source = new byte[0];
        byte[] target = new byte[0];
        byte[] snapshot = new byte[0];
        String status = AgentMigrationTaskStatus.PREPARED.name();
        String reason = "";
        long updatedAtMillis = 0L;
        String leaseOwner = "";
        long leaseExpiresAtMillis = 0L;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> taskId = input.readString();
                    case 2 -> identity = input.readByteArray();
                    case 3 -> source = input.readByteArray();
                    case 4 -> target = input.readByteArray();
                    case 5 -> snapshot = input.readByteArray();
                    case 6 -> status = input.readString();
                    case 7 -> reason = input.readString();
                    case 8 -> updatedAtMillis = input.readInt64();
                    case 9 -> leaseOwner = input.readString();
                    case 10 -> leaseExpiresAtMillis = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return new AgentMigrationTask(
                    taskId,
                    decodeIdentity(identity),
                    decodeLocation(source),
                    decodeLocation(target),
                    decodeSnapshot(snapshot),
                    AgentMigrationTaskStatus.valueOf(status),
                    reason,
                    Instant.ofEpochMilli(updatedAtMillis),
                    leaseOwner,
                    Instant.ofEpochMilli(leaseExpiresAtMillis)
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode agent migration task", e);
        }
    }

    private static byte[] encodeIdentity(AgentIdentity identity) {
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

    private static AgentIdentity decodeIdentity(byte[] bytes) {
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

    private static byte[] encodeLocation(AgentLocation location) {
        ServiceId serviceId = location.serviceId();
        int size = CodedOutputStream.computeStringSize(1, serviceId.kind().name())
                + CodedOutputStream.computeStringSize(2, serviceId.region())
                + CodedOutputStream.computeStringSize(3, serviceId.node())
                + CodedOutputStream.computeStringSize(4, location.actorRef().id());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, serviceId.kind().name());
            output.writeString(2, serviceId.region());
            output.writeString(3, serviceId.node());
            output.writeString(4, location.actorRef().id());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode agent location", e);
        }
    }

    private static AgentLocation decodeLocation(byte[] bytes) {
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
            return new AgentLocation(new ServiceId(ServiceKind.valueOf(kind), region, node), new ActorRef(actorId));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode agent location", e);
        }
    }

    private static byte[] encodeSnapshot(AgentMigrationSnapshot snapshot) {
        byte[] stateBytes = snapshot.stateBytes();
        int size = CodedOutputStream.computeStringSize(1, snapshot.stateType())
                + CodedOutputStream.computeByteArraySize(2, stateBytes);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, snapshot.stateType());
            output.writeByteArray(2, stateBytes);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode agent migration snapshot", e);
        }
    }

    private static AgentMigrationSnapshot decodeSnapshot(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String stateType = "";
        byte[] stateBytes = new byte[0];
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> stateType = input.readString();
                    case 2 -> stateBytes = input.readByteArray();
                    default -> input.skipField(tag);
                }
            }
            return new AgentMigrationSnapshot(stateType, stateBytes);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode agent migration snapshot", e);
        }
    }
}
