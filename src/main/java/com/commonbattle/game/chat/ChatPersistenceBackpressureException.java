package com.commonbattle.game.chat;

/**
 * Chat Agent 恢复或保存期间的有界等待队列已满。
 */
public final class ChatPersistenceBackpressureException extends RuntimeException {
    public ChatPersistenceBackpressureException(int capacity) {
        super("Chat persistence pending queue is full: capacity=" + capacity);
    }
}
