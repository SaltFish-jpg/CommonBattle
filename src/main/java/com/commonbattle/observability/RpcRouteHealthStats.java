package com.commonbattle.observability;

import com.commonbattle.cluster.rpc.RpcRouteStats;

import java.util.Map;
import java.util.Objects;

/**
 * RPC 路由策略聚合健康统计。
 */
public record RpcRouteHealthStats(
        int viewCount,
        long calls,
        long routedCalls,
        long unroutedCalls,
        Map<String, Long> routeTagCalls
) {
    public RpcRouteHealthStats {
        routeTagCalls = Map.copyOf(Objects.requireNonNull(routeTagCalls, "routeTagCalls"));
    }

    public static RpcRouteHealthStats empty() {
        return new RpcRouteHealthStats(0, 0, 0, 0, Map.of());
    }

    public static RpcRouteHealthStats from(int viewCount, RpcRouteStats stats) {
        return new RpcRouteHealthStats(
                viewCount,
                stats.calls(),
                stats.routedCalls(),
                stats.unroutedCalls(),
                stats.routeTagCalls()
        );
    }
}
