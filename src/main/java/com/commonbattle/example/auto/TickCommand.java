package com.commonbattle.example.auto;

import com.commonbattle.core.AttackCommand;
import com.commonbattle.core.BattleContext;
import com.commonbattle.core.Command;
import com.commonbattle.core.Effect;
import com.commonbattle.core.Entity;
import com.commonbattle.core.EntityId;

import java.util.List;

/**
 * Auto-battle heartbeat.
 * Each tick advances time and queues an attack from the first alive player unit to the first alive enemy unit.
 */
public record TickCommand() implements Command {
    @Override
    public List<Effect> effects(BattleContext context) {
        context.state().advanceTick();
        EntityId attacker = AutoBattleRuleSet.firstAliveInFaction(context, "player").id();
        EntityId target = AutoBattleRuleSet.firstAliveInFaction(context, "enemy").id();
        return List.of(new SubmitCommandEffect(new AttackCommand(attacker, target)));
    }
}
