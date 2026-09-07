package com.commonbattle.runtime;

/**
 * 服务排水阶段。
 * 对外摘流必须早于本地关闭入口，给注册订阅、路由缓存和代理转发留出传播窗口。
 */
public enum DrainPhase {
    EXTERNAL_ADVERTISEMENT,
    LOCAL_INGRESS
}
