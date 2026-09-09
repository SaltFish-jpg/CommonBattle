package com.commonbattle.cluster;

/**
 * 注册中心推送给订阅者的服务变更事件。
 */
public record RegistryEvent(RegistryEventType type, ServiceDescriptor service, long version) {
    public RegistryEvent(RegistryEventType type, ServiceDescriptor service) {
        this(type, service, 0);
    }

    public RegistryEvent {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(service, "service");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }
}
