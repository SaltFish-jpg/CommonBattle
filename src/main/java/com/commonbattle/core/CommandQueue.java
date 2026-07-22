package com.commonbattle.core;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;

/**
 * 玩家、AI、规则或效果提交命令时使用的先进先出队列。
 */
public final class CommandQueue {
    private final Queue<Command> queue = new ArrayDeque<>();

    public void add(Command command) {
        queue.add(command);
    }

    public Optional<Command> poll() {
        return Optional.ofNullable(queue.poll());
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
