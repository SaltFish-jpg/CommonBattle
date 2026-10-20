package com.commonbattle.game.chat;

import java.util.Objects;

/**
 * Chat Agent 的持久化快照，version 用于写入顺序 CAS，ownerToken 用于拒绝旧租约延迟写。
 */
public record VersionedChatState(long version, long ownerToken, ChatChannelSnapshot snapshot) {
    public VersionedChatState {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (ownerToken < 0) {
            throw new IllegalArgumentException("ownerToken must not be negative");
        }
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
