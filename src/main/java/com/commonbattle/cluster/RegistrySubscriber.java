package com.commonbattle.cluster;

/**
 * 服务发现订阅者。
 * Game、Scene、Region、Proxy 等节点通过订阅注册中心变化来维护本地路由表。
 */
@FunctionalInterface
public interface RegistrySubscriber {
    void onEvent(RegistryEvent event);
}
