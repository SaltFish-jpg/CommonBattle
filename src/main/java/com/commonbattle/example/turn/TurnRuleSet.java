package com.commonbattle.example.turn;

import com.commonbattle.core.AttackCommand;
import com.commonbattle.core.BasicRuleSet;
import com.commonbattle.core.BattleContext;
import com.commonbattle.core.Command;

/**
 * Example rule set for turn-based games.
 * It reuses basic attack validation and adds current-actor checks.
 */
public final class TurnRuleSet extends BasicRuleSet {
    @Override
    public void validate(BattleContext context, Command command) {
        super.validate(context, command);
        TurnComponent turn = context.state().requireGlobal(TurnComponent.class);
        if (command instanceof AttackCommand attack && !turn.currentActor().equals(attack.attacker())) {
            throw new IllegalStateException("It is not this attacker's turn");
        }
        if (command instanceof EndTurnCommand endTurn && !turn.currentActor().equals(endTurn.actor())) {
            throw new IllegalStateException("Only current actor can end turn");
        }
    }
}
