package com.commonbattle.cluster.registry;

/**
 * 动态服务描述发布器统计快照。
 */
public record ServiceDescriptorPublisherStats(
        long attempts,
        long succeeded,
        long failed,
        boolean draining
) {
}
