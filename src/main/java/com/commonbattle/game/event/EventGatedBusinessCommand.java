package com.commonbattle.game.event;

/**
 * 由 owner 版本事件约束一致性边界的业务命令。
 * 跨服 RPC、Netty 回调或订阅事件只负责构造这个命令并投递，真正的状态读写仍回到目标业务 Actor mailbox。
 *
 * @param <E> 作为一致性边界的 owner 事件类型
 * @param <C> 需要在业务 Actor 内执行的命令 payload
 */
public interface EventGatedBusinessCommand<E extends VersionedEvent, C> {
    E event();

    C command();

    default EventGatedCommandBoundary boundary() {
        return EventGatedCommandBoundary.from(event());
    }
}
