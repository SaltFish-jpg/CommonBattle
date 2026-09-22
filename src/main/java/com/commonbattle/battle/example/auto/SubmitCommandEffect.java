package com.commonbattle.battle.example.auto;




import com.commonbattle.battle.command.Command;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.Effect;
public record SubmitCommandEffect(Command command) implements Effect {
    @Override
    public void apply(BattleContext context) {
        context.submit(command);
        context.log().add("auto.command_queued", command.getClass().getSimpleName() + " queued");
    }
}
