package com.commonbattle.game.social;

/**
 * 好友列表快照变更监听口。
 * FriendAgent 在 owner mailbox 内写完好友快照后调用它，常用于投影 Profile 的 FriendBrief 摘要。
 */
@FunctionalInterface
public interface FriendSummaryListener {
    void onFriendSnapshot(FriendSnapshot snapshot);

    static FriendSummaryListener noop() {
        return snapshot -> {
        };
    }
}
