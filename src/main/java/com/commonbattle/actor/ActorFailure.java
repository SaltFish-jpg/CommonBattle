package com.commonbattle.actor;

/**
 * Actor 任务执行失败记录。
 */
public record ActorFailure(ActorRef actor, ActorTask task, Throwable error) {
}
