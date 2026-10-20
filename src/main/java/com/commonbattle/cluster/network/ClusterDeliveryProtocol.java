package com.commonbattle.cluster.network;

import com.commonbattle.cluster.ServiceId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 跨服信封逐跳投递的基础协议。
 * 代理服用它记录请求返回路径，并在下游写入失败时向原始发送方返回可识别的失败回执。
 */
public final class ClusterDeliveryProtocol {
    public static final String FAILURE_OPERATION = "$cluster.delivery.failure";
    public static final String ACK_OPERATION = "$cluster.delivery.ack";
    public static final String RETURN_PROXY = "cluster.return_proxy";
    public static final String DELIVERY_ID = "cluster.delivery_id";
    public static final String ACK_TARGET = "cluster.delivery_ack_target";
    public static final String FAILED_TARGET = "cluster.failed_target";
    public static final String FAILED_OPERATION = "cluster.failed_operation";

    private ClusterDeliveryProtocol() {
    }

    public static ClusterEnvelope throughProxy(ClusterEnvelope envelope, ServiceId proxy) {
        return throughProxy(envelope, proxy, "");
    }

    public static ClusterEnvelope throughProxy(ClusterEnvelope envelope, ServiceId proxy, String deliveryId) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Map<String, String> metadata = new HashMap<>(envelope.metadata());
        metadata.put(RETURN_PROXY, proxy.wireName());
        return forHop(withMetadata(envelope, metadata), proxy, deliveryId);
    }

    public static ClusterEnvelope forHop(ClusterEnvelope envelope, ServiceId acknowledgmentTarget, String deliveryId) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(acknowledgmentTarget, "acknowledgmentTarget");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Map<String, String> metadata = new HashMap<>(envelope.metadata());
        if (deliveryId.isBlank()) {
            metadata.remove(DELIVERY_ID);
            metadata.remove(ACK_TARGET);
        } else {
            metadata.put(DELIVERY_ID, deliveryId);
            metadata.put(ACK_TARGET, acknowledgmentTarget.wireName());
        }
        return withMetadata(envelope, metadata);
    }

    public static ClusterEnvelope acknowledgment(ServiceId receiver, ClusterEnvelope delivered) {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(delivered, "delivered");
        String acknowledgmentTarget = delivered.metadata().get(ACK_TARGET);
        String deliveryId = delivered.metadata().get(DELIVERY_ID);
        if (acknowledgmentTarget == null || !ServiceId.isWireName(acknowledgmentTarget)
                || deliveryId == null || deliveryId.isBlank()) {
            return null;
        }
        return new ClusterEnvelope(
                delivered.requestId(),
                receiver,
                ServiceId.parse(acknowledgmentTarget),
                ACK_OPERATION,
                null,
                Map.of(DELIVERY_ID, deliveryId)
        );
    }

    public static ClusterEnvelope failure(ServiceId proxy, ClusterEnvelope failed, Throwable error) {
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(failed, "failed");
        Map<String, String> metadata = new HashMap<>();
        metadata.put(FAILED_TARGET, failed.target().wireName());
        metadata.put(FAILED_OPERATION, failed.operation());
        String deliveryId = failed.metadata().get(DELIVERY_ID);
        if (deliveryId != null && !deliveryId.isBlank()) {
            metadata.put(DELIVERY_ID, deliveryId);
        }
        String reason = error == null || error.getMessage() == null ? "" : error.getMessage();
        return new ClusterEnvelope(
                failed.requestId(),
                proxy,
                failed.source(),
                FAILURE_OPERATION,
                reason,
                metadata
        );
    }

    public static ServiceId returnProxy(ClusterEnvelope envelope) {
        String wireName = Objects.requireNonNull(envelope, "envelope").metadata().get(RETURN_PROXY);
        if (wireName == null || wireName.isBlank() || !ServiceId.isWireName(wireName)) {
            return null;
        }
        return ServiceId.parse(wireName);
    }

    public static boolean isFailure(ClusterEnvelope envelope) {
        return FAILURE_OPERATION.equals(Objects.requireNonNull(envelope, "envelope").operation());
    }

    public static boolean isAcknowledgment(ClusterEnvelope envelope) {
        return ACK_OPERATION.equals(Objects.requireNonNull(envelope, "envelope").operation());
    }

    private static ClusterEnvelope withMetadata(ClusterEnvelope envelope, Map<String, String> metadata) {
        return new ClusterEnvelope(
                envelope.requestId(),
                envelope.source(),
                envelope.target(),
                envelope.operation(),
                envelope.payload(),
                metadata
        );
    }
}
