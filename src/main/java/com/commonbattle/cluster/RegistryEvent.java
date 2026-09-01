package com.commonbattle.cluster;

/**
 * 注册中心推送给订阅者的服务变更事件。
 */
public record RegistryEvent(RegistryEventType type, ServiceDescriptor service) {
}
