package com.commonbattle.core;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;

/**
 * FIFO queue for commands submitted by players, AI, rules, or effects.
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
