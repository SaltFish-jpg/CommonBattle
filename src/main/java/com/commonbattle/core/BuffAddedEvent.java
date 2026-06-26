package com.commonbattle.core;

/**
 * Published after a buff is attached to an entity.
 */
public record BuffAddedEvent(EntityId target, Buff buff) implements Event {
}
