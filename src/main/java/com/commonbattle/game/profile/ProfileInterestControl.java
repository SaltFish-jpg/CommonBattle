package com.commonbattle.game.profile;

import java.util.Collection;

/**
 * Profile owner 关注生命周期控制口。
 * 场景、聊天等业务 Agent 只调用 watch/unwatch，不关心底层是中心订阅、远程回源还是本地空实现。
 */
public interface ProfileInterestControl {
    void watch(long playerId);

    void unwatch(long playerId);

    default void watchAll(Collection<Long> playerIds) {
        playerIds.forEach(this::watch);
    }

    default void unwatchAll(Collection<Long> playerIds) {
        playerIds.forEach(this::unwatch);
    }

    default void requestRepair(long playerId) {
        requestRepairAll(java.util.Set.of(playerId));
    }

    default void requestRepairAll(Collection<Long> playerIds) {
    }

    static ProfileInterestControl noop() {
        return Noop.INSTANCE;
    }

    enum Noop implements ProfileInterestControl {
        INSTANCE;

        @Override
        public void watch(long playerId) {
        }

        @Override
        public void unwatch(long playerId) {
        }

        @Override
        public void requestRepairAll(Collection<Long> playerIds) {
        }
    }
}
