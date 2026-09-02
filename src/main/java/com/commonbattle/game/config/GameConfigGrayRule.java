package com.commonbattle.game.config;

/**
 * 配置灰度规则。
 * percent 表示命中 0-100 桶的比例，按玩家 id 稳定取模。
 */
public record GameConfigGrayRule(long version, int percent) {
    public GameConfigGrayRule {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent must be between 0 and 100");
        }
    }

    public boolean matches(long playerId) {
        int bucket = Math.floorMod(Long.hashCode(playerId), 100);
        return bucket < percent;
    }
}
