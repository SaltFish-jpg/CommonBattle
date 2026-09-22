package com.commonbattle.actor.agent.migration;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.time.Instant;

/**
 * 基于 protobuf wire format 的目标迁入回执序列化器。
 * 字段号只允许尾部追加，避免灰度和回滚期间旧回执无法解码。
 */
public final class ProtoAgentMigrationTargetReceiptSerializer implements AgentMigrationTargetReceiptSerializer {
    @Override
    public byte[] encode(AgentMigrationTargetReceipt receipt) {
        int size = CodedOutputStream.computeStringSize(1, receipt.taskId())
                + CodedOutputStream.computeBoolSize(2, receipt.response().accepted())
                + CodedOutputStream.computeStringSize(3, receipt.response().reason())
                + CodedOutputStream.computeInt64Size(4, receipt.updatedAt().toEpochMilli());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, receipt.taskId());
            output.writeBool(2, receipt.response().accepted());
            output.writeString(3, receipt.response().reason());
            output.writeInt64(4, receipt.updatedAt().toEpochMilli());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode migration target receipt", e);
        }
    }

    @Override
    public AgentMigrationTargetReceipt decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String taskId = "";
        boolean accepted = false;
        String reason = "";
        long updatedAtMillis = 0L;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> taskId = input.readString();
                    case 2 -> accepted = input.readBool();
                    case 3 -> reason = input.readString();
                    case 4 -> updatedAtMillis = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            return new AgentMigrationTargetReceipt(
                    taskId,
                    new AgentMigrationAcceptResponse(accepted, reason),
                    Instant.ofEpochMilli(updatedAtMillis)
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode migration target receipt", e);
        }
    }
}
