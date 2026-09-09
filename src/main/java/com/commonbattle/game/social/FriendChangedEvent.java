package com.commonbattle.game.social;

import com.commonbattle.game.event.VersionedEvent;

/**
 * 玩家好友列表变更事件。
 * playerId 是好友列表 owner，revision 是该玩家好友列表的单调版本。
 */
public record FriendChangedEvent(
        long playerId,
        long friendId,
        FriendRelationAction action,
        long revision
) implements VersionedEvent {
    public static final String TOPIC = "friend.changed";

    public FriendChangedEvent() {
        this(1, 2, FriendRelationAction.ADD, 1);
    }

    public FriendChangedEvent {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (friendId <= 0) {
            throw new IllegalArgumentException("friendId must be positive");
        }
        if (playerId == friendId) {
            throw new IllegalArgumentException("friendId must not be self");
        }
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String ownerKey() {
        return FriendOwnerKeyParser.ownerKey(playerId);
    }
}
