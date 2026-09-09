package com.commonbattle.observability;

import com.commonbattle.cluster.ServiceKind;

import java.util.Map;
import java.util.Objects;

/**
 * 本地服务目录中已发现的跨服服务数量和路由标签分布。
 */
public record ClusterServiceStats(
        Map<ServiceKind, Integer> counts,
        Map<ServiceKind, Integer> drainingCounts,
        Map<ServiceKind, Long> versions,
        Map<ServiceKind, Map<String, Integer>> routeTagCounts,
        Map<ServiceKind, Map<String, Integer>> deploymentGroupCounts
) {
    public ClusterServiceStats {
        counts = Map.copyOf(Objects.requireNonNull(counts, "counts"));
        drainingCounts = Map.copyOf(Objects.requireNonNull(drainingCounts, "drainingCounts"));
        versions = Map.copyOf(Objects.requireNonNull(versions, "versions"));
        routeTagCounts = copyNested(routeTagCounts);
        deploymentGroupCounts = copyNested(deploymentGroupCounts);
    }

    public ClusterServiceStats(
            Map<ServiceKind, Integer> counts,
            Map<ServiceKind, Integer> drainingCounts
    ) {
        this(counts, drainingCounts, Map.of(), Map.of(), Map.of());
    }

    public ClusterServiceStats(Map<ServiceKind, Integer> counts) {
        this(counts, Map.of(), Map.of(), Map.of(), Map.of());
    }

    public int count(ServiceKind kind) {
        return counts.getOrDefault(kind, 0);
    }

    public int draining(ServiceKind kind) {
        return drainingCounts.getOrDefault(kind, 0);
    }

    public long version(ServiceKind kind) {
        return versions.getOrDefault(kind, 0L);
    }

    public int routeTag(ServiceKind kind, String tag) {
        return routeTagCounts.getOrDefault(kind, Map.of()).getOrDefault(tag, 0);
    }

    public int deploymentGroup(ServiceKind kind, String group) {
        return deploymentGroupCounts.getOrDefault(kind, Map.of()).getOrDefault(group, 0);
    }

    private static Map<ServiceKind, Map<String, Integer>> copyNested(Map<ServiceKind, Map<String, Integer>> values) {
        Objects.requireNonNull(values, "values");
        return values.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> Map.copyOf(entry.getValue())
                ));
    }
}
