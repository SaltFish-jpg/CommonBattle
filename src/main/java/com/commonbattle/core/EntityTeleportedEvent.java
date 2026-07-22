package com.commonbattle.core;

/**
 * 实体传送到新坐标后发布的事件。
 */
public record EntityTeleportedEvent(EntityId entity, int fromX, int fromY, int toX, int toY) implements Event {
}
