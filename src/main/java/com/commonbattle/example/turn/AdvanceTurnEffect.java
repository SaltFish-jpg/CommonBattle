package com.commonbattle.example.turn;

import com.commonbattle.core.BattleContext;
import com.commonbattle.core.Effect;
import com.commonbattle.core.EntityId;
import com.commonbattle.core.TriggerTiming;

public record AdvanceTurnEffect(EntityId actor) implements Effect {
    @Override
    public void apply(BattleContext context) {
        TurnComponent turn = context.state().requireGlobal(TurnComponent.class);
        context.triggerSystem().fire(TriggerTiming.TURN_END, context);
        turn.advance();
        context.log().add("turn.advance", "turn advanced from %s to %s".formatted(actor.value(), turn.currentActor().value()));
        context.triggerSystem().fire(TriggerTiming.TURN_START, context);
    }
}
