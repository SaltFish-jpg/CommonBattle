package com.commonbattle.game.player.event;

import com.commonbattle.game.snapshot.SnapshotOwnerKeyParser;

import java.util.OptionalLong;

/**
 * 解析 PlayerDomainVersionedEvent 的 ownerKey。
 */
public final class PlayerDomainOwnerKeyParser implements SnapshotOwnerKeyParser {
    public static final PlayerDomainOwnerKeyParser INSTANCE = new PlayerDomainOwnerKeyParser();
    private static final String PREFIX = "player:";

    private PlayerDomainOwnerKeyParser() {
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
}
