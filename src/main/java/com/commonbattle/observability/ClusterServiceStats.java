package com.commonbattle.observability;

import com.commonbattle.cluster.ServiceKind;

import java.util.Map;

/**
 * 本地服务目录中已发现的跨服服务数量。
 */
public record ClusterServiceStats(
        Map<ServiceKind, Integer> counts,
        Map<ServiceKind, Integer> drainingCounts
) {
    public ClusterServiceStats {
        counts = Map.copyOf(counts);
        drainingCounts = Map.copyOf(drainingCounts);
    }

    public ClusterServiceStats(Map<ServiceKind, Integer> counts) {
        this(counts, Map.of());
    }

    public int count(ServiceKind kind) {
        return counts.getOrDefault(kind, 0);
    }

    public int draining(ServiceKind kind) {
        return drainingCounts.getOrDefault(kind, 0);
    }
}
