package com.commonbattle.game.event;

/**
 * 事件门控命令审计写入端。
 * RPC/Netty 入口可在只投递消息的同时记录命令边界，不需要触碰业务状态。
 */
@FunctionalInterface
public interface EventGatedCommandAuditSink {
    void record(EventGatedCommandAuditRecord record);

    static EventGatedCommandAuditSink noop() {
        return ignored -> {
        };
    }
}
