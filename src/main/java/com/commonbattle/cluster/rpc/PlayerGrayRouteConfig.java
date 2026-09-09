package com.commonbattle.cluster.rpc;

import java.util.Objects;
import java.util.Set;

/**
 * 玩家维度灰度路由配置。
 * operations 为空表示对所有 RPC 操作生效；通常线上会只配置跨服 Scene 或目标业务的操作名。
 */
public record PlayerGrayRouteConfig(
        boolean enabled,
        String stableTag,
        String grayTag,
        int grayPercent,
        Set<Long> playerWhitelist,
        Set<String> operations
) {
    public PlayerGrayRouteConfig {
        Objects.requireNonNull(stableTag, "stableTag");
        Objects.requireNonNull(grayTag, "grayTag");
        playerWhitelist = Set.copyOf(Objects.requireNonNull(playerWhitelist, "playerWhitelist"));
        operations = Set.copyOf(Objects.requireNonNull(operations, "operations"));
        if (stableTag.isBlank()) {
            throw new IllegalArgumentException("stableTag must not be blank");
        }
        if (grayTag.isBlank()) {
            throw new IllegalArgumentException("grayTag must not be blank");
        }
        if (grayPercent < 0 || grayPercent > 100) {
            throw new IllegalArgumentException("grayPercent must be between 0 and 100");
        }
        if (operations.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("operations must not contain blank value");
        }
    }

    public static PlayerGrayRouteConfig disabled() {
        return new PlayerGrayRouteConfig(false, "stable", "gray", 0, Set.of(), Set.of());
    }
}
