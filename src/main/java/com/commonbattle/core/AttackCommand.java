package com.commonbattle.core;

import java.util.List;

/**
 * Basic physical attack command.
 * Damage is read from the attacker's {@link AttributeComponent} named {@code attack}.
 */
public record AttackCommand(EntityId attacker, EntityId target) implements Command {
    @Override
    public List<Effect> effects(BattleContext context) {
        Entity source = context.state().requireEntity(attacker);
        int amount = AttributeValueResolver.resolve(source, "attack");
        return List.of(new DealDamageEffect(attacker, target, amount));
    }
}
