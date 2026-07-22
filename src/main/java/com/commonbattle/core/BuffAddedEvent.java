package com.commonbattle.core;

/**
 * Buff 挂到实体后发布的事件。
 */
public record BuffAddedEvent(EntityId target, Buff buff) implements Event {
}
