package com.commonbattle.example.card;

import com.commonbattle.core.BattleContext;
import com.commonbattle.core.Effect;
import com.commonbattle.core.EntityId;

public record MoveCardEffect(EntityId card, Zone to) implements Effect {
    @Override
    public void apply(BattleContext context) {
        context.state().requireEntity(card).require(ZoneComponent.class).moveTo(to);
        context.log().add("card.zone_changed", "%s moved to %s".formatted(card.value(), to));
    }
}
