package com.commonbattle.battle.example.turn;




import com.commonbattle.battle.command.Command;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.Effect;
import com.commonbattle.battle.state.EntityId;
import java.util.List;

public record EndTurnCommand(EntityId actor) implements Command {
    @Override
    public List<Effect> effects(BattleContext context) {
        return List.of(new AdvanceTurnEffect(actor));
    }
}
