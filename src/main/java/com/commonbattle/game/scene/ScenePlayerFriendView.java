package com.commonbattle.game.scene;

import java.util.Set;

/**
 * 场景内玩家好友列表只读视图。
 */
public record ScenePlayerFriendView(long playerId, long revision, Set<Long> friends, boolean stale) {
    public ScenePlayerFriendView {
        friends = Set.copyOf(friends);
    }

    public boolean contains(long friendId) {
        return friends.contains(friendId);
    }
}
