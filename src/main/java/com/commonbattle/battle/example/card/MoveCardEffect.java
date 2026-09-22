package com.commonbattle.battle.example.card;




import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.state.EntityId;
public record MoveCardEffect(EntityId card, Zone to) implements Effect {
    @Override
    public void apply(BattleContext context) {
        context.state().requireEntity(card).require(ZoneComponent.class).moveTo(to);
        context.log().add("card.zone_changed", "%s moved to %s".formatted(card.value(), to));
    }
}
