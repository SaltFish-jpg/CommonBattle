package com.commonbattle.game.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Center 游戏配置注册表的启动恢复器。
 * 先重放所有历史版本，再恢复 active/gray 指针，使回滚能力和玩家灰度选择在重启后保持一致。
 */
public final class GameConfigCenterRecovery {
    private GameConfigCenterRecovery() {
    }

    public static Optional<GameConfigSnapshot> restore(
            GameConfigRegistry registry,
            GameConfigCenterStateStore stateStore
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(stateStore, "stateStore");
        Optional<GameConfigSnapshot> recovered = stateStore.loadSnapshot();
        if (recovered.isEmpty()) {
            return Optional.empty();
        }
        GameConfigSnapshot snapshot = recovered.orElseThrow();
        Map<Long, GameConfigPackage> versions = new LinkedHashMap<>();
        stateStore.loadVersions().forEach(config -> versions.put(config.version(), config));
        versions.put(snapshot.activeConfig().version(), snapshot.activeConfig());
        snapshot.optionalGrayConfig().ifPresent(config -> versions.put(config.version(), config));
        List<GameConfigPackage> ordered = new ArrayList<>(versions.values());
        ordered.sort(Comparator.comparingLong(GameConfigPackage::version));
        for (GameConfigPackage config : ordered) {
            GameConfigPublishResult result = registry.publish(config);
            if (result.status() != GameConfigPublishStatus.PUBLISHED) {
                throw new IllegalStateException("Failed to restore game config version "
                        + config.version() + ": " + result.message());
            }
        }
        GameConfigPublishResult rollback = registry.rollback(snapshot.activeConfig().version());
        if (rollback.status() != GameConfigPublishStatus.ROLLED_BACK) {
            throw new IllegalStateException("Failed to restore active game config "
                    + snapshot.activeConfig().version() + ": " + rollback.message());
        }
        if (snapshot.grayConfig() != null) {
            GameConfigPublishResult gray = registry.publishGray(snapshot.grayConfig(), snapshot.grayPercent());
            if (gray.status() != GameConfigPublishStatus.GRAY_PUBLISHED) {
                throw new IllegalStateException("Failed to restore gray game config "
                        + snapshot.grayConfig().version() + ": " + gray.message());
            }
        }
        return recovered;
    }
}
