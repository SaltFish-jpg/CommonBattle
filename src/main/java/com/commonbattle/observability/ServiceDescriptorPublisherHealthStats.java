package com.commonbattle.observability;

/**
 * 动态服务描述发布器聚合健康指标。
 */
public record ServiceDescriptorPublisherHealthStats(
        int publisherCount,
        int drainingPublishers,
        long attempts,
        long succeeded,
        long failed
) {
    public static ServiceDescriptorPublisherHealthStats empty() {
        return new ServiceDescriptorPublisherHealthStats(0, 0, 0, 0, 0);
    }
}
