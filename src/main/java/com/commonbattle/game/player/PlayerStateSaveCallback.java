package com.commonbattle.game.player;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 单次玩家状态保存回调。
 * 自动保存、下线保存和运维排水需要区分保存成功与持久化失败时使用。
 */
public interface PlayerStateSaveCallback {
    void saved(PlayerStateSnapshot snapshot);

    void failed(long playerId, RuntimeException error);

    static PlayerStateSaveCallback onSaved(Consumer<PlayerStateSnapshot> callback) {
        Objects.requireNonNull(callback, "callback");
        return new PlayerStateSaveCallback() {
            @Override
            public void saved(PlayerStateSnapshot snapshot) {
                callback.accept(snapshot);
            }

            @Override
            public void failed(long playerId, RuntimeException error) {
            }
        };
    }
}
