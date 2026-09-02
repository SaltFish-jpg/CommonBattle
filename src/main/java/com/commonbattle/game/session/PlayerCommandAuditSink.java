package com.commonbattle.game.session;

/**
 * 玩家命令审计落点。
 * 生产环境可实现为日志、Kafka、审计 DB 或链路追踪系统。
 */
@FunctionalInterface
public interface PlayerCommandAuditSink {
    PlayerCommandAuditSink NOOP = record -> {
    };

    void record(PlayerCommandAuditRecord record);
}
