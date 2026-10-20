package com.commonbattle.game.player;

import java.util.Optional;

/**
 * 玩家完成响应检查点存储。
 * 调用方必须在玩家状态和命令水位保存成功后再写入，避免重启后重放尚未持久化的业务结果。
 */
public interface PlayerBusinessResponseStore {
    Optional<PlayerBusinessResponseSnapshot> load(long playerId);

    void save(PlayerBusinessResponseSnapshot snapshot);

    static PlayerBusinessResponseStore noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final PlayerBusinessResponseStore INSTANCE = new PlayerBusinessResponseStore() {
            @Override
            public Optional<PlayerBusinessResponseSnapshot> load(long playerId) {
                return Optional.empty();
            }

            @Override
            public void save(PlayerBusinessResponseSnapshot snapshot) {
            }
        };

        private NoopHolder() {
        }
    }
}
