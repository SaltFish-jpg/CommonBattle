package com.commonbattle.cluster.event;

import com.commonbattle.cluster.protocol.EncodedPayload;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * 中心事件日志使用的稳定 wire payload。
 */
record StoredEventPayload(String codecName, String typeName, byte[] bytes) {
    StoredEventPayload {
        Objects.requireNonNull(codecName, "codecName");
        Objects.requireNonNull(typeName, "typeName");
        bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
    }

    static byte[] encode(EncodedPayload payload) {
        int size = CodedOutputStream.computeStringSize(1, payload.codecName())
                + CodedOutputStream.computeStringSize(2, payload.typeName())
                + CodedOutputStream.computeByteArraySize(3, payload.bytes());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, payload.codecName());
            output.writeString(2, payload.typeName());
            output.writeByteArray(3, payload.bytes());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode stored event payload", e);
        }
    }

    static StoredEventPayload decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String codecName = "";
        String typeName = "";
        byte[] payload = new byte[0];
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> codecName = input.readString();
                    case 2 -> typeName = input.readString();
                    case 3 -> payload = input.readByteArray();
                    default -> input.skipField(tag);
                }
            }
            return new StoredEventPayload(codecName, typeName, payload);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode stored event payload", e);
        }
    }
}
