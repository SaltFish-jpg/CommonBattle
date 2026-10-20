package com.commonbattle.game.chat;

/**
 * Chat 持久化版本或 owner fencing 冲突，当前 Agent 必须停止继续提交状态。
 */
public final class ChatPersistenceConflictException extends RuntimeException {
    public ChatPersistenceConflictException(ChatStateSaveStatus status) {
        super("Chat persistence conflict: " + status);
    }
}
