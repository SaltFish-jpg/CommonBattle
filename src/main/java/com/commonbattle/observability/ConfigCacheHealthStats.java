package com.commonbattle.observability;

/**
 * 本地配置缓存健康统计。
 * cacheCount 为纳入探针的配置缓存数量，readyCaches 表示已经完成预热且没有事件缺口的缓存数量。
 */
public record ConfigCacheHealthStats(
        int cacheCount,
        int activeCaches,
        int readyCaches,
        int staleCaches,
        long minAppliedEventRevision,
        long maxAppliedEventRevision
) {
    public ConfigCacheHealthStats {
        if (cacheCount < 0 || activeCaches < 0 || readyCaches < 0 || staleCaches < 0) {
            throw new IllegalArgumentException("cache counts must not be negative");
        }
    }

    public static ConfigCacheHealthStats empty() {
        return new ConfigCacheHealthStats(0, 0, 0, 0, 0, 0);
    }
}
