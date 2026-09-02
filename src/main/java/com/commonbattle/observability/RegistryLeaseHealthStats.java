package com.commonbattle.observability;

/**
 * 注册租约健康统计。
 * renewers 观察节点侧心跳，reapers 观察中心侧过期摘除。
 */
public record RegistryLeaseHealthStats(
        long renewers,
        long successfulHeartbeats,
        long reRegistrations,
        long failedRenewals,
        long reapers,
        long expiredServices
) {
    public RegistryLeaseHealthStats {
        if (renewers < 0 || successfulHeartbeats < 0 || reRegistrations < 0
                || failedRenewals < 0 || reapers < 0 || expiredServices < 0) {
            throw new IllegalArgumentException("registry lease stats must not be negative");
        }
    }

    public static RegistryLeaseHealthStats empty() {
        return new RegistryLeaseHealthStats(0, 0, 0, 0, 0, 0);
    }
}
