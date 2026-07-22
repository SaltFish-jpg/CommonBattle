package com.commonbattle.core;

import java.util.List;

/**
 * 在效果结算时选择目标。
 * 这样技能可以保持数据驱动，同时仍允许不同玩法提供自己的选敌逻辑。
 */
@FunctionalInterface
public interface TargetSelector {
    List<EntityId> select(BattleContext context);

    static TargetSelector enemiesOf(String faction) {
        return context -> context.state().entities().stream()
                .filter(entity -> entity.find(FactionComponent.class)
                        .map(component -> !component.faction().equals(faction))
                        .orElse(false))
                .filter(entity -> entity.find(HealthComponent.class).map(HealthComponent::alive).orElse(false))
                .map(Entity::id)
                .toList();
    }
}
