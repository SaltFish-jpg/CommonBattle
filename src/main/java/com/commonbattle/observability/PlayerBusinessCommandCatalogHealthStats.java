package com.commonbattle.observability;

import com.commonbattle.game.player.PlayerBusinessCommandCatalogStats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 玩家业务命令目录健康统计。
 * 多个运行时视图会被聚合，便于在同一进程内同时暴露主命令目录和扩展命令目录。
 */
public record PlayerBusinessCommandCatalogHealthStats(
        int viewCount,
        int descriptorCount,
        int operationCount,
        int commandTypeCount,
        Map<String, Integer> descriptorsByDomain
) {
    public PlayerBusinessCommandCatalogHealthStats {
        if (viewCount < 0 || descriptorCount < 0 || operationCount < 0 || commandTypeCount < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        descriptorsByDomain = Map.copyOf(Objects.requireNonNull(descriptorsByDomain, "descriptorsByDomain"));
    }

    public static PlayerBusinessCommandCatalogHealthStats empty() {
        return new PlayerBusinessCommandCatalogHealthStats(0, 0, 0, 0, Map.of());
    }

    public static PlayerBusinessCommandCatalogHealthStats from(
            int viewCount,
            PlayerBusinessCommandCatalogStats stats
    ) {
        return new PlayerBusinessCommandCatalogHealthStats(
                viewCount,
                stats.descriptorCount(),
                stats.operationCount(),
                stats.commandTypeCount(),
                stats.descriptorsByDomain()
        );
    }

    public PlayerBusinessCommandCatalogHealthStats plus(PlayerBusinessCommandCatalogHealthStats other) {
        Objects.requireNonNull(other, "other");
        LinkedHashMap<String, Integer> domains = new LinkedHashMap<>(descriptorsByDomain);
        other.descriptorsByDomain.forEach((domain, count) -> domains.merge(domain, count, Integer::sum));
        return new PlayerBusinessCommandCatalogHealthStats(
                viewCount + other.viewCount,
                descriptorCount + other.descriptorCount,
                operationCount + other.operationCount,
                commandTypeCount + other.commandTypeCount,
                domains
        );
    }
}
