package com.commonbattle.game.social;

import com.commonbattle.game.snapshot.SnapshotOwnerKeyParser;

import java.util.OptionalLong;

/**
 * 解析 FriendChangedEvent 的 ownerKey。
 */
public final class FriendOwnerKeyParser implements SnapshotOwnerKeyParser {
    public static final FriendOwnerKeyParser INSTANCE = new FriendOwnerKeyParser();
    private static final String PREFIX = "friend:";

    private FriendOwnerKeyParser() {
    }

    @Override
    public OptionalLong parse(String ownerKey) {
        if (ownerKey == null || !ownerKey.startsWith(PREFIX)) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(ownerKey.substring(PREFIX.length())));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    public static String ownerKey(long playerId) {
        return PREFIX + playerId;
    }
}
