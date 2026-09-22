package com.commonbattle.battle.event;



import com.commonbattle.battle.buff.Buff;
import com.commonbattle.battle.state.EntityId;
/**
 * Buff 挂到实体后发布的事件。
 */
public record BuffAddedEvent(EntityId target, Buff buff) implements Event {
}
