package com.commonbattle.cluster.registry;

/**
 * 注册租约续约器统计。
 * 用于观察心跳是否稳定，以及节点是否发生过因中心丢失租约而重新注册。
 */
public record RegistryLeaseRenewalStats(
        long successfulHeartbeats,
        long reRegistrations,
        long failedRenewals
) {
    public RegistryLeaseRenewalStats {
        if (successfulHeartbeats < 0 || reRegistrations < 0 || failedRenewals < 0) {
            throw new IllegalArgumentException("lease renewal stats must not be negative");
        }
    }
}
