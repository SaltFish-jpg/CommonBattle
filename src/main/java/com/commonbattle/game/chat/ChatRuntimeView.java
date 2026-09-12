package com.commonbattle.game.chat;

/**
 * Chat 运行时只读观测视图。
 */
@FunctionalInterface
public interface ChatRuntimeView {
    ChatServiceStats stats();
}
