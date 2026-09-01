package com.commonbattle.actor;

/**
 * Actor 邮箱中的一条业务消息。
 * 实现方把网络包、RPC 回包、定时器等外部输入封装成任务，交给目标 Actor 串行执行。
 */
@FunctionalInterface
public interface ActorTask {
    void run(ActorContext context);
}
