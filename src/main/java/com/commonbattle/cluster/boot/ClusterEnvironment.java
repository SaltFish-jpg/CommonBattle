package com.commonbattle.cluster.boot;

/**
 * 集群配置的运行环境等级。
 * PRODUCTION 会启用持久化与运维安全守卫，DEVELOPMENT 保留本地示例的轻量默认值。
 */
public enum ClusterEnvironment {
    DEVELOPMENT,
    PRODUCTION
}
