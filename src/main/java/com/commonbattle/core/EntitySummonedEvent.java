package com.commonbattle.core;

/**
 * 召唤物创建后发布的事件。
 */
public record EntitySummonedEvent(EntityId summoner, EntityId summoned, String type) implements Event {
}
