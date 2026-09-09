package com.commonbattle.game.event;

import java.util.Collection;

/**
 * owner 粒度的事件关注控制口。
 * 业务 Agent 在自己的 mailbox 内调用它，把“我现在需要哪些 owner 的事件”同步给跨服订阅层。
 */
public interface OwnerEventInterestControl {
    void watchOwner(String ownerKey);

    void unwatchOwner(String ownerKey);

    default void watchOwners(Collection<String> ownerKeys) {
        ownerKeys.forEach(this::watchOwner);
    }

    default void unwatchOwners(Collection<String> ownerKeys) {
        ownerKeys.forEach(this::unwatchOwner);
    }

    default void requestRepairOwner(String ownerKey) {
        requestRepairOwners(java.util.Set.of(ownerKey));
    }

    default void requestRepairOwners(Collection<String> ownerKeys) {
    }

    static OwnerEventInterestControl noop() {
        return Noop.INSTANCE;
    }

    enum Noop implements OwnerEventInterestControl {
        INSTANCE;

        @Override
        public void watchOwner(String ownerKey) {
        }

        @Override
        public void unwatchOwner(String ownerKey) {
        }

        @Override
        public void watchOwners(Collection<String> ownerKeys) {
        }

        @Override
        public void unwatchOwners(Collection<String> ownerKeys) {
        }

        @Override
        public void requestRepairOwners(Collection<String> ownerKeys) {
        }
    }

}
