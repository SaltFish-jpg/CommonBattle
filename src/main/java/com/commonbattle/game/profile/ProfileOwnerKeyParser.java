package com.commonbattle.game.profile;

import com.commonbattle.game.snapshot.SnapshotOwnerKeyParser;

import java.util.OptionalLong;

/**
 * 解析 ProfileChangedEvent 的 ownerKey。
 */
public final class ProfileOwnerKeyParser implements SnapshotOwnerKeyParser {
    public static final ProfileOwnerKeyParser INSTANCE = new ProfileOwnerKeyParser();
    private static final String PREFIX = "profile:";

    private ProfileOwnerKeyParser() {
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
