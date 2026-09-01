package com.commonbattle.cluster.network;

import com.commonbattle.cluster.ServiceId;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/**
 * 跨服链路上传输的统一信封。
 * source/target 负责服务级路由，operation/payload 交给上层 RPC 或消息处理器解释。
 */
public record ClusterEnvelope(
        long requestId,
        ServiceId source,
        ServiceId target,
        String operation,
        Object payload,
        Map<String, String> metadata
) implements Serializable {
    public ClusterEnvelope(long requestId, ServiceId source, ServiceId target, String operation, Object payload) {
        this(requestId, source, target, operation, payload, Map.of());
    }

    public ClusterEnvelope {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operation, "operation");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (operation.isBlank()) {
            throw new IllegalArgumentException("Cluster operation must not be blank");
        }
    }
}
