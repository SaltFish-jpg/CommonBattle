package com.commonbattle.cluster.boot;

/**
 * 启动配置中的一条校验问题。
 */
public record ClusterConfigIssue(String key, String message) {
}
