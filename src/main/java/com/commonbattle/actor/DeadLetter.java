package com.commonbattle.actor;

/**
 * 无法投递给目标 Actor 的消息。
 * 死信用于监控、告警和补偿，不应在死信处理器里直接执行原始业务任务。
 */
public record DeadLetter(ActorRef target, ActorTask task, DeadLetterReason reason) {
}
