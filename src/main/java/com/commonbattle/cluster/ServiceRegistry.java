package com.commonbattle.cluster;

import java.util.List;

/**
 * 跨服服务注册中心。
 * 实现方可以是本地内存、Redis、etcd 或中心服；调用方只依赖注册、取消注册、查询和订阅这组语义。
 */
public interface ServiceRegistry {
    void register(ServiceDescriptor service);

    void unregister(ServiceId serviceId);

    List<ServiceDescriptor> list(ServiceKind kind);

    AutoCloseable subscribe(ServiceKind kind, RegistrySubscriber subscriber);
}
