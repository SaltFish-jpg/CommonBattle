package com.commonbattle.core;

import java.util.List;

/**
 * Selects targets at effect resolution time.
 * This keeps skills data-driven while still allowing mode-specific targeting logic.
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
