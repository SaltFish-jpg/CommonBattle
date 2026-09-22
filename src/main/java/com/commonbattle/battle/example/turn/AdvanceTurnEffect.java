package com.commonbattle.battle.example.turn;




import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.state.EntityId;
import com.commonbattle.battle.trigger.TriggerSystem;
import com.commonbattle.battle.trigger.TriggerTiming;
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
