package com.commonbattle.game.event;

/**
 * 快照型版本事件发布端口。
 * 状态 owner 在 mailbox 内完成内存修改后，把最新快照和对应事件一起交给该端口，端口负责固定的持久化与发布边界。
 */
@FunctionalInterface
public interface SnapshotEventPublisher<S, E extends VersionedEvent> {
    void publish(S snapshot, E event);
}
