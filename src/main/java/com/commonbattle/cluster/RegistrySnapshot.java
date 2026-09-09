package com.commonbattle.cluster;

import java.util.List;
import java.util.Objects;

/**
 * 注册目录某类服务的全量快照。
 * version 是生成快照时注册中心的全局事件版本，订阅端可用它补齐快照后的增量变更。
 */
public record RegistrySnapshot(ServiceKind kind, List<ServiceDescriptor> services, long version) {
    public RegistrySnapshot {
        Objects.requireNonNull(kind, "kind");
        services = List.copyOf(Objects.requireNonNull(services, "services"));
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }
}
