package com.commonbattle.example.auto;

import com.commonbattle.core.BattleContext;
import com.commonbattle.core.Command;
import com.commonbattle.core.Effect;

public record SubmitCommandEffect(Command command) implements Effect {
    @Override
    public void apply(BattleContext context) {
        context.submit(command);
        context.log().add("auto.command_queued", command.getClass().getSimpleName() + " queued");
    }
}
