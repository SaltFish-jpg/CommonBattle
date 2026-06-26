package com.commonbattle.example.card;

import com.commonbattle.core.BattleContext;
import com.commonbattle.core.Effect;
import com.commonbattle.core.EntityId;

public record SpendManaEffect(EntityId player, EntityId card) implements Effect {
    @Override
    public void apply(BattleContext context) {
        int cost = context.state().requireEntity(card).require(CostComponent.class).mana();
        context.state().requireEntity(player).require(ManaComponent.class).spend(cost);
        context.log().add("card.mana_spent", "%s spent %d mana".formatted(player.value(), cost));
    }
}
