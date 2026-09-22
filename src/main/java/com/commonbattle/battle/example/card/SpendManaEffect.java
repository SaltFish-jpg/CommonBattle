package com.commonbattle.battle.example.card;




import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.state.EntityId;
public record SpendManaEffect(EntityId player, EntityId card) implements Effect {
    @Override
    public void apply(BattleContext context) {
        int cost = context.state().requireEntity(card).require(CostComponent.class).mana();
        context.state().requireEntity(player).require(ManaComponent.class).spend(cost);
        context.log().add("card.mana_spent", "%s spent %d mana".formatted(player.value(), cost));
    }
}
