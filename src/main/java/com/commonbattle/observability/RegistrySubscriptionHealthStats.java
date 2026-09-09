package com.commonbattle.observability;

/**
 * 注册中心订阅表健康统计。
 */
public record RegistrySubscriptionHealthStats(
        int viewCount,
        int subscribedKinds,
        int subscribers,
        int references,
        long subscribeRequests,
        long unsubscribeRequests,
        long cleanedSubscribers,
        long expiredSubscriptions
) {
    public RegistrySubscriptionHealthStats {
        if (viewCount < 0
                || subscribedKinds < 0
                || subscribers < 0
                || references < 0
                || subscribeRequests < 0
                || unsubscribeRequests < 0
                || cleanedSubscribers < 0
                || expiredSubscriptions < 0) {
            throw new IllegalArgumentException("registry subscription health stats must not be negative");
        }
    }

    public static RegistrySubscriptionHealthStats empty() {
        return new RegistrySubscriptionHealthStats(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
