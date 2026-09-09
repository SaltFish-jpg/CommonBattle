package com.commonbattle.cluster.rpc;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * RPC 路由策略命中统计。
 * routeTagCalls 记录最终写入调用选项的 route tag，便于验证灰度流量是否真正命中目标标签。
 */
public record RpcRouteStats(
        long calls,
        long routedCalls,
        long unroutedCalls,
        Map<String, Long> routeTagCalls
) {
    public RpcRouteStats {
        routeTagCalls = Map.copyOf(Objects.requireNonNull(routeTagCalls, "routeTagCalls"));
    }

    public static RpcRouteStats empty() {
        return new RpcRouteStats(0, 0, 0, Map.of());
    }

    public RpcRouteStats plus(RpcRouteStats other) {
        Objects.requireNonNull(other, "other");
        Map<String, Long> tags = new HashMap<>(routeTagCalls);
        other.routeTagCalls.forEach((tag, count) -> tags.merge(tag, count, Long::sum));
        return new RpcRouteStats(
                calls + other.calls,
                routedCalls + other.routedCalls,
                unroutedCalls + other.unroutedCalls,
                tags
        );
    }
}
