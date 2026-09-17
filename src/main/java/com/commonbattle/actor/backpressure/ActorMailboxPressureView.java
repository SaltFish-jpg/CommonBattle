package com.commonbattle.actor.backpressure;

/**
 * Actor 邮箱压力准入控制的只读观测视图。
 * 启动流程注册该视图后，健康探针可聚合玩家命令和通用业务 Agent 的压力拒绝情况。
 */
public interface ActorMailboxPressureView {
    ActorMailboxPressureStats mailboxPressureStats();
}
