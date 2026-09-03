package com.commonbattle.cluster;

import java.io.Serializable;
import java.util.Objects;

/**
 * 一个跨服服务实例的稳定身份。
 * region 表示逻辑大区，node 表示该类型服务在大区内的实例名。
 */
public record ServiceId(ServiceKind kind, String region, String node) implements Serializable {
    public ServiceId {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(node, "node");
        if (region.isBlank()) {
            throw new IllegalArgumentException("Service region must not be blank");
        }
        if (node.isBlank()) {
            throw new IllegalArgumentException("Service node must not be blank");
        }
    }

    public static ServiceId of(ServiceKind kind, String region, String node) {
        return new ServiceId(kind, region, node);
    }

    public static ServiceId parse(String value) {
        Objects.requireNonNull(value, "value");
        String[] parts = value.split(":", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid service id: " + value);
        }
        return new ServiceId(ServiceKind.valueOf(parts[0].toUpperCase(java.util.Locale.ROOT)), parts[1], parts[2]);
    }

    public static boolean isWireName(String value) {
        return value != null && value.chars().filter(ch -> ch == ':').count() == 2;
    }

    public String wireName() {
        return kind.name().toLowerCase() + ":" + region + ":" + node;
    }
}
