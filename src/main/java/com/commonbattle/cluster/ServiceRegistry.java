package com.commonbattle.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 跨服服务注册中心。
 * 实现方可以是本地内存、Redis、etcd 或中心服；调用方只依赖注册、取消注册、查询和订阅这组语义。
 */
public interface ServiceRegistry {
    void register(ServiceDescriptor service);

    default void register(ServiceDescriptor service, Duration leaseTtl) {
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        register(service);
    }

    default boolean heartbeat(ServiceId serviceId, Duration leaseTtl) {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        return false;
    }

    default int expireLeases(Instant now) {
        Objects.requireNonNull(now, "now");
        return 0;
    }

    void unregister(ServiceId serviceId);

    List<ServiceDescriptor> list(ServiceKind kind);

    AutoCloseable subscribe(ServiceKind kind, RegistrySubscriber subscriber);
}
