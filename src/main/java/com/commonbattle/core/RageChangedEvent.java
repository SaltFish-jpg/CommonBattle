package com.commonbattle.core;

/**
 * 怒气变化后发布的事件，便于客户端同步能量条或统计回怒来源。
 */
public record RageChangedEvent(EntityId entity, int amount, int current, int max, String reason) implements Event {
}
