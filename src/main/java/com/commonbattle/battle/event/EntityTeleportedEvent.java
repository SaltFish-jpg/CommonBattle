package com.commonbattle.battle.event;



import com.commonbattle.battle.state.Entity;
import com.commonbattle.battle.state.EntityId;
/**
 * 实体传送到新坐标后发布的事件。
 */
public record EntityTeleportedEvent(EntityId entity, int fromX, int fromY, int toX, int toY) implements Event {
}
