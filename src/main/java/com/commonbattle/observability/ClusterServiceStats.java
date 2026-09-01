package com.commonbattle.observability;

import com.commonbattle.cluster.ServiceKind;

import java.util.Map;

/**
 * 本地服务目录中已发现的跨服服务数量。
 */
public record ClusterServiceStats(Map<ServiceKind, Integer> counts) {
    public ClusterServiceStats {
        counts = Map.copyOf(counts);
    }

    public int count(ServiceKind kind) {
        return counts.getOrDefault(kind, 0);
    }
}
