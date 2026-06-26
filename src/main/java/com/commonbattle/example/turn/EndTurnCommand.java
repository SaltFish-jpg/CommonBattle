package com.commonbattle.example.turn;

import com.commonbattle.core.BattleContext;
import com.commonbattle.core.Command;
import com.commonbattle.core.Effect;
import com.commonbattle.core.EntityId;

import java.util.List;

public record EndTurnCommand(EntityId actor) implements Command {
    @Override
    public List<Effect> effects(BattleContext context) {
        return List.of(new AdvanceTurnEffect(actor));
    }
}
