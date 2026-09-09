package com.commonbattle.cluster.registry;

/**
 * 暴露中心注册订阅表水位。
 * 中心服用它观察服务订阅是否随节点下线被正确清理。
 */
public interface RegistrySubscriptionView {
    RegistrySubscriptionStats stats();
}
