package com.commonbattle.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 服务注册 metadata 的通用约定。
 * 新增服务状态时优先扩展 metadata，避免注册中心接口和 wire 协议频繁变更。
 */
public final class ServiceMetadata {
    public static final String DRAINING = "service.draining";
    public static final String PROTOCOL_VERSION = "service.protocol.version";
    public static final String LOAD_USED = "service.load.used";
    public static final String LOAD_CAPACITY = "service.load.capacity";
    public static final String ROUTE_TAG = "service.route.tag";
    public static final String DEPLOYMENT_GROUP = "service.deployment.group";
    public static final int DEFAULT_PROTOCOL_VERSION = 1;
    public static final long UNKNOWN_LOAD_SCORE = 0L;

    private ServiceMetadata() {
    }

    public static boolean draining(ServiceDescriptor service) {
        return Boolean.parseBoolean(service.metadata(DRAINING));
    }

    public static ServiceDescriptor withDraining(ServiceDescriptor service, boolean draining) {
        Map<String, String> metadata = new HashMap<>(service.metadata());
        if (draining) {
            metadata.put(DRAINING, "true");
        } else {
            metadata.remove(DRAINING);
        }
        return service.withMetadata(metadata);
    }

    public static int protocolVersion(ServiceDescriptor service) {
        String value = service.metadata(PROTOCOL_VERSION);
        if (value == null || value.isBlank()) {
            return DEFAULT_PROTOCOL_VERSION;
        }
        return Integer.parseInt(value);
    }

    public static ServiceDescriptor withProtocolVersion(ServiceDescriptor service, int version) {
        if (version <= 0) {
            throw new IllegalArgumentException("protocol version must be positive");
        }
        Map<String, String> metadata = new HashMap<>(service.metadata());
        metadata.put(PROTOCOL_VERSION, String.valueOf(version));
        return service.withMetadata(metadata);
    }

    public static ServiceDescriptor withLoad(ServiceDescriptor service, int used, int capacity) {
        if (used < 0) {
            throw new IllegalArgumentException("load used must not be negative");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("load capacity must be positive");
        }
        Map<String, String> metadata = new HashMap<>(service.metadata());
        metadata.put(LOAD_USED, String.valueOf(used));
        metadata.put(LOAD_CAPACITY, String.valueOf(capacity));
        return service.withMetadata(metadata);
    }

    public static ServiceDescriptor withRouteTag(ServiceDescriptor service, String tag) {
        requireMetadataValue(ROUTE_TAG, tag);
        Map<String, String> metadata = new HashMap<>(service.metadata());
        metadata.put(ROUTE_TAG, tag);
        return service.withMetadata(metadata);
    }

    public static String routeTag(ServiceDescriptor service) {
        return service.metadata(ROUTE_TAG);
    }

    public static boolean matches(ServiceDescriptor service, Map<String, String> requiredMetadata) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(requiredMetadata, "requiredMetadata");
        for (Map.Entry<String, String> entry : requiredMetadata.entrySet()) {
            requireMetadataValue(entry.getKey(), entry.getValue());
            if (!Objects.equals(service.metadata(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    public static long loadScore(ServiceDescriptor service) {
        int capacity = positiveInt(service.metadata(LOAD_CAPACITY), 0);
        if (capacity <= 0) {
            return UNKNOWN_LOAD_SCORE;
        }
        int used = Math.max(0, positiveInt(service.metadata(LOAD_USED), 0));
        return (long) used * 1000L / capacity;
    }

    private static int positiveInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    private static void requireMetadataValue(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (key.isBlank()) {
            throw new IllegalArgumentException("metadata key must not be blank");
        }
    }
}
