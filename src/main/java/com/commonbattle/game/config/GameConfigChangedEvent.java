package com.commonbattle.game.config;

import com.commonbattle.game.event.VersionedEvent;

import java.util.Objects;

/**
 * 游戏配置变更事件。
 * revision 是配置发布流水号，必须单调递增；config.version 是业务配置版本，回滚时可以小于当前版本。
 */
public record GameConfigChangedEvent(
        long revision,
        GameConfigChangeType changeType,
        GameConfigPackage config,
        int grayPercent
) implements VersionedEvent {
    public static final String TOPIC = "game.config.changed";
    public static final String OWNER_KEY = "game-config";

    public GameConfigChangedEvent {
        Objects.requireNonNull(changeType, "changeType");
        Objects.requireNonNull(config, "config");
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (grayPercent < 0 || grayPercent > 100) {
            throw new IllegalArgumentException("grayPercent must be between 0 and 100");
        }
        if (changeType != GameConfigChangeType.GRAY_PUBLISHED && grayPercent != 0) {
            throw new IllegalArgumentException("grayPercent is only valid for gray publish");
        }
    }

    public static GameConfigChangedEvent activePublished(long revision, GameConfigPackage config) {
        return new GameConfigChangedEvent(revision, GameConfigChangeType.ACTIVE_PUBLISHED, config, 0);
    }

    public static GameConfigChangedEvent grayPublished(long revision, GameConfigPackage config, int percent) {
        return new GameConfigChangedEvent(revision, GameConfigChangeType.GRAY_PUBLISHED, config, percent);
    }

    public static GameConfigChangedEvent rolledBack(long revision, GameConfigPackage config) {
        return new GameConfigChangedEvent(revision, GameConfigChangeType.ROLLED_BACK, config, 0);
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String ownerKey() {
        return OWNER_KEY;
    }
}
