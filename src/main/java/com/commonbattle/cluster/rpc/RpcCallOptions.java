package com.commonbattle.cluster.rpc;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 单次跨服 RPC 调用选项。
 * idempotencyKey 用于业务重试去重；requiredTargetMetadata 用于灰度、标签和版本路由。
 */
public record RpcCallOptions(
        Duration timeout,
        String idempotencyKey,
        Map<String, String> requiredTargetMetadata
) {
    public RpcCallOptions {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        requiredTargetMetadata = Map.copyOf(Objects.requireNonNull(requiredTargetMetadata, "requiredTargetMetadata"));
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        requiredTargetMetadata.forEach(RpcCallOptions::requireMetadataEntry);
    }

    public RpcCallOptions(Duration timeout, String idempotencyKey) {
        this(timeout, idempotencyKey, Map.of());
    }

    public static RpcCallOptions of(Duration timeout) {
        return new RpcCallOptions(timeout, "", Map.of());
    }

    public RpcCallOptions withIdempotencyKey(String key) {
        return new RpcCallOptions(timeout, key, requiredTargetMetadata);
    }

    public RpcCallOptions withRequiredTargetMetadata(String key, String value) {
        requireMetadataEntry(key, value);
        Map<String, String> metadata = new HashMap<>(requiredTargetMetadata);
        metadata.put(key, value);
        return new RpcCallOptions(timeout, idempotencyKey, metadata);
    }

    public RpcCallOptions withRequiredTargetMetadata(Map<String, String> metadata) {
        Objects.requireNonNull(metadata, "metadata");
        metadata.forEach(RpcCallOptions::requireMetadataEntry);
        Map<String, String> merged = new HashMap<>(requiredTargetMetadata);
        merged.putAll(metadata);
        return new RpcCallOptions(timeout, idempotencyKey, merged);
    }

    public RpcCallOptions withoutRequiredTargetMetadata(String key) {
        Objects.requireNonNull(key, "key");
        Map<String, String> metadata = new HashMap<>(requiredTargetMetadata);
        metadata.remove(key);
        return new RpcCallOptions(timeout, idempotencyKey, metadata);
    }

    private static void requireMetadataEntry(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (key.isBlank()) {
            throw new IllegalArgumentException("metadata key must not be blank");
        }
    }
}
