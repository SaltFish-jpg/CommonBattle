package com.commonbattle.cluster.registry;

/**
 * 中心注册订阅表的运行时统计。
 */
public record RegistrySubscriptionStats(
        int subscribedKinds,
        int subscribers,
        int references,
        long subscribeRequests,
        long unsubscribeRequests,
        long cleanedSubscribers,
        long expiredSubscriptions
) {
    public RegistrySubscriptionStats {
        if (subscribedKinds < 0
                || subscribers < 0
                || references < 0
                || subscribeRequests < 0
                || unsubscribeRequests < 0
                || cleanedSubscribers < 0
                || expiredSubscriptions < 0) {
            throw new IllegalArgumentException("registry subscription stats must not be negative");
        }
    }

    public static RegistrySubscriptionStats empty() {
        return new RegistrySubscriptionStats(0, 0, 0, 0, 0, 0, 0);
    }
}
