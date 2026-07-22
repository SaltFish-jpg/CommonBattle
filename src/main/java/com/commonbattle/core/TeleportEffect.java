package com.commonbattle.core;

/**
 * 将已有实体移动到指定坐标的传送效果。
 */
public record TeleportEffect(EntityId entity, int x, int y) implements Effect {
    @Override
    public void apply(BattleContext context) {
        PositionComponent position = context.state().requireEntity(entity).require(PositionComponent.class);
        int fromX = position.x();
        int fromY = position.y();
        position.moveTo(x, y);
        context.log().add("effect.teleport", "%s teleported from (%d,%d) to (%d,%d)"
                .formatted(entity.value(), fromX, fromY, x, y));
        context.eventBus().publish(new EntityTeleportedEvent(entity, fromX, fromY, x, y));
    }
}
