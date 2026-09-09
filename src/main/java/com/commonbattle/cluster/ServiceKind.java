package com.commonbattle.cluster;

/**
 * 跨服网络中的服务类型。
 * 路由、注册订阅和代理转发都以服务类型作为第一层边界。
 */
public enum ServiceKind {
    CENTER,
    REGION,
    GAME,
    CHAT,
    SCENE,
    PROXY
}
