package com.commonbattle.core;

public record UnitDamagedEvent(EntityId source, EntityId target, int amount, int remainingHealth) implements Event {
}
