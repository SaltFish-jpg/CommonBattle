package com.commonbattle.game.player;

/**
 * 玩家状态保存监听器。
 * 用于在玩家邮箱保存成功后投影资料快照、写审计或触发轻量异步补偿。
 */
@FunctionalInterface
public interface PlayerStateSaveListener {
    void saved(PlayerStateSnapshot snapshot);

    static PlayerStateSaveListener ignore() {
        return ignored -> {
        };
    }
}
