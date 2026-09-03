package com.commonbattle.cluster.boot;

import java.time.Duration;
import java.util.Objects;

/**
 * 本地集群启动等待策略。
 * timeout 控制单个服务最长等待时间，pollInterval 控制探针重试间隔。
 */
public record ClusterStartupPolicy(Duration timeout, Duration pollInterval) {
    public ClusterStartupPolicy {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
    }

    public static ClusterStartupPolicy noWait() {
        return new ClusterStartupPolicy(Duration.ZERO, Duration.ofMillis(10));
    }

    public static ClusterStartupPolicy defaults() {
        return new ClusterStartupPolicy(Duration.ofSeconds(10), Duration.ofMillis(200));
    }
}
