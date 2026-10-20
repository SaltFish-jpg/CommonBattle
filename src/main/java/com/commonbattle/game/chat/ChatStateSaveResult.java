package com.commonbattle.game.chat;

import java.util.Objects;
import java.util.Optional;

/**
 * Chat 快照写入结果；冲突时携带当前状态，供调用方停止旧 owner 或重新恢复。
 */
public record ChatStateSaveResult(ChatStateSaveStatus status, Optional<VersionedChatState> current) {
    public ChatStateSaveResult {
        Objects.requireNonNull(status, "status");
        current = Objects.requireNonNull(current, "current");
        if (status == ChatStateSaveStatus.APPLIED && current.isEmpty()) {
            throw new IllegalArgumentException("applied result must include state");
        }
    }

    public static ChatStateSaveResult applied(VersionedChatState state) {
        return new ChatStateSaveResult(ChatStateSaveStatus.APPLIED, Optional.of(state));
    }

    public static ChatStateSaveResult conflict(VersionedChatState state) {
        return new ChatStateSaveResult(ChatStateSaveStatus.VERSION_CONFLICT, Optional.of(state));
    }

    public static ChatStateSaveResult staleOwner(VersionedChatState state) {
        return new ChatStateSaveResult(ChatStateSaveStatus.STALE_OWNER, Optional.of(state));
    }
}
