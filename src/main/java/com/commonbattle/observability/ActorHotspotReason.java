package com.commonbattle.observability;

/**
 * Actor 被判定为热点候选的主要信号。
 */
public enum ActorHotspotReason {
    QUEUE_BACKLOG,
    SLOW_TASK_COUNT,
    SLOW_TASK_MILLIS
}
