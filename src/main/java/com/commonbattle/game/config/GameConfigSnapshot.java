package com.commonbattle.game.config;

import java.util.Objects;
import java.util.Optional;

/**
 * 配置中心返回给业务进程的完整配置快照。
 * activeConfig 是正式版本；grayConfig 存在时表示当前仍有灰度版本。
 */
public record GameConfigSnapshot(
        long eventRevision,
        GameConfigPackage activeConfig,
        GameConfigPackage grayConfig,
        int grayPercent
) {
    public GameConfigSnapshot {
        if (eventRevision < 0) {
            throw new IllegalArgumentException("eventRevision must not be negative");
        }
        Objects.requireNonNull(activeConfig, "activeConfig");
        if (grayConfig == null && grayPercent != 0) {
            throw new IllegalArgumentException("grayPercent requires grayConfig");
        }
        if (grayPercent < 0 || grayPercent > 100) {
            throw new IllegalArgumentException("grayPercent must be between 0 and 100");
        }
    }

    public static GameConfigSnapshot activeOnly(long eventRevision, GameConfigPackage activeConfig) {
        return new GameConfigSnapshot(eventRevision, activeConfig, null, 0);
    }

    public static GameConfigSnapshot withGray(
            long eventRevision,
            GameConfigPackage activeConfig,
            GameConfigPackage grayConfig,
            int grayPercent
    ) {
        return new GameConfigSnapshot(eventRevision, activeConfig, grayConfig, grayPercent);
    }

    public Optional<GameConfigPackage> optionalGrayConfig() {
        return Optional.ofNullable(grayConfig);
    }
}
