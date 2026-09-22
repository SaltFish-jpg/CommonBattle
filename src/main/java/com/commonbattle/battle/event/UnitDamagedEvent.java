package com.commonbattle.battle.event;



import com.commonbattle.battle.state.EntityId;
public record UnitDamagedEvent(EntityId source, EntityId target, int amount, int remainingHealth) implements Event {
}
