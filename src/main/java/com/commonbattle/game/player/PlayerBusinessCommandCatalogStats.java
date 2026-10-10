package com.commonbattle.game.player;

import java.util.Map;
import java.util.Objects;

/**
 * 玩家业务命令目录统计。
 * 运维侧用它确认当前进程暴露了哪些业务命令域和稳定操作名，避免灰度进程遗漏命令绑定。
 */
public record PlayerBusinessCommandCatalogStats(
        int descriptorCount,
        int operationCount,
        int commandTypeCount,
        Map<String, Integer> descriptorsByDomain
) {
    public PlayerBusinessCommandCatalogStats {
        if (descriptorCount < 0 || operationCount < 0 || commandTypeCount < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        descriptorsByDomain = Map.copyOf(Objects.requireNonNull(descriptorsByDomain, "descriptorsByDomain"));
    }

    public static PlayerBusinessCommandCatalogStats empty() {
        return new PlayerBusinessCommandCatalogStats(0, 0, 0, Map.of());
    }

    public PlayerBusinessCommandCatalogStats plus(PlayerBusinessCommandCatalogStats other) {
        Objects.requireNonNull(other, "other");
        java.util.LinkedHashMap<String, Integer> domains = new java.util.LinkedHashMap<>(descriptorsByDomain);
        other.descriptorsByDomain.forEach((domain, count) -> domains.merge(domain, count, Integer::sum));
        return new PlayerBusinessCommandCatalogStats(
                descriptorCount + other.descriptorCount,
                operationCount + other.operationCount,
                commandTypeCount + other.commandTypeCount,
                domains
        );
    }
}
