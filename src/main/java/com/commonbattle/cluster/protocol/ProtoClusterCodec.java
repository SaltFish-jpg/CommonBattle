package com.commonbattle.cluster.protocol;

import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * ClusterEnvelope 的 protobuf wire 编解码。
 * 信封保持稳定 protobuf 结构，payload 通过 payload_codec/payload_type/payload_bytes 支持多编码。
 */
public final class ProtoClusterCodec {
    private final PayloadCodecRegistry payloadCodecs;

    public ProtoClusterCodec(PayloadCodecRegistry payloadCodecs) {
        this.payloadCodecs = payloadCodecs;
    }

    public byte[] encode(ClusterEnvelope envelope) {
        EncodedPayload payload = payloadCodecs.encode(envelope.payload());
        int size = CodedOutputStream.computeInt64Size(1, envelope.requestId())
                + stringSize(2, envelope.source().kind().name())
                + stringSize(3, envelope.source().region())
                + stringSize(4, envelope.source().node())
                + stringSize(5, envelope.target().kind().name())
                + stringSize(6, envelope.target().region())
                + stringSize(7, envelope.target().node())
                + stringSize(8, envelope.operation())
                + stringSize(9, payload.codecName())
                + stringSize(10, payload.typeName())
                + CodedOutputStream.computeByteArraySize(11, payload.bytes());
        for (Map.Entry<String, String> entry : envelope.metadata().entrySet()) {
            size += CodedOutputStream.computeByteArraySize(12, encodeMetadata(entry.getKey(), entry.getValue()));
        }
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, envelope.requestId());
            output.writeString(2, envelope.source().kind().name());
            output.writeString(3, envelope.source().region());
            output.writeString(4, envelope.source().node());
            output.writeString(5, envelope.target().kind().name());
            output.writeString(6, envelope.target().region());
            output.writeString(7, envelope.target().node());
            output.writeString(8, envelope.operation());
            output.writeString(9, payload.codecName());
            output.writeString(10, payload.typeName());
            output.writeByteArray(11, payload.bytes());
            for (Map.Entry<String, String> entry : envelope.metadata().entrySet()) {
                output.writeByteArray(12, encodeMetadata(entry.getKey(), entry.getValue()));
            }
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode cluster envelope", e);
        }
    }

    public ClusterEnvelope decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long requestId = 0;
        String sourceKind = null;
        String sourceRegion = null;
        String sourceNode = null;
        String targetKind = null;
        String targetRegion = null;
        String targetNode = null;
        String operation = null;
        String payloadCodec = PayloadEncoding.PROTOBUF;
        String payloadType = null;
        byte[] payloadBytes = new byte[0];
        Map<String, String> metadata = new HashMap<>();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> requestId = input.readInt64();
                    case 2 -> sourceKind = input.readString();
                    case 3 -> sourceRegion = input.readString();
                    case 4 -> sourceNode = input.readString();
                    case 5 -> targetKind = input.readString();
                    case 6 -> targetRegion = input.readString();
                    case 7 -> targetNode = input.readString();
                    case 8 -> operation = input.readString();
                    case 9 -> payloadCodec = input.readString();
                    case 10 -> payloadType = input.readString();
                    case 11 -> payloadBytes = input.readByteArray();
                    case 12 -> metadata.putAll(decodeMetadata(input.readByteArray()));
                    default -> input.skipField(tag);
                }
            }
            return new ClusterEnvelope(
                    requestId,
                    ServiceId.of(ServiceKind.valueOf(sourceKind), sourceRegion, sourceNode),
                    ServiceId.of(ServiceKind.valueOf(targetKind), targetRegion, targetNode),
                    operation,
                    payloadCodecs.decode(payloadCodec, payloadType, payloadBytes),
                    metadata
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode cluster envelope", e);
        }
    }

    private static int stringSize(int fieldNumber, String value) {
        return CodedOutputStream.computeStringSize(fieldNumber, value);
    }

    private static byte[] encodeMetadata(String key, String value) {
        int size = stringSize(1, key) + stringSize(2, value);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, key);
            output.writeString(2, value);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode cluster metadata", e);
        }
    }

    private static Map<String, String> decodeMetadata(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String key = "";
        String value = "";
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> key = input.readString();
                    case 2 -> value = input.readString();
                    default -> input.skipField(tag);
                }
            }
            return key.isBlank() ? Map.of() : Map.of(key, value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode cluster metadata", e);
        }
    }
}
