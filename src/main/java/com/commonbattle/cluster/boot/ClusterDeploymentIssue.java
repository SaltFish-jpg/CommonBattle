package com.commonbattle.cluster.boot;

/**
 * 部署清单校验问题。
 * 用于在启动前或测试中一次性暴露缺字段、配置漂移、启动类缺失等错误。
 */
public record ClusterDeploymentIssue(String serviceName, String key, String message) {
    public ClusterDeploymentIssue {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message required");
        }
    }
}
