package com.commonbattle.game.config;

import java.util.List;
import java.util.Optional;

/**
 * Center 权威游戏配置状态存储。
 * 实现需要持久化历史版本以及包含事件 revision 的 active/gray 快照，供进程重启和回滚恢复。
 */
public interface GameConfigCenterStateStore {
    Optional<GameConfigSnapshot> loadSnapshot();

    List<GameConfigPackage> loadVersions();

    void saveVersion(GameConfigPackage config);

    void saveSnapshot(GameConfigSnapshot snapshot);

    static GameConfigCenterStateStore noop() {
        return Noop.INSTANCE;
    }

    enum Noop implements GameConfigCenterStateStore {
        INSTANCE;

        @Override
        public Optional<GameConfigSnapshot> loadSnapshot() {
            return Optional.empty();
        }

        @Override
        public List<GameConfigPackage> loadVersions() {
            return List.of();
        }

        @Override
        public void saveVersion(GameConfigPackage config) {
        }

        @Override
        public void saveSnapshot(GameConfigSnapshot snapshot) {
        }
    }
}
