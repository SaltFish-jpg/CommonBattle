package com.commonbattle.example.auto;

import com.commonbattle.core.BasicRuleSet;
import com.commonbattle.core.BattleContext;
import com.commonbattle.core.Command;
import com.commonbattle.core.Entity;
import com.commonbattle.core.FactionComponent;
import com.commonbattle.core.HealthComponent;

/**
 * Example rule set for automatic battle.
 * A tick is legal only while both sides still have an alive unit.
 */
public final class AutoBattleRuleSet extends BasicRuleSet {
    @Override
    public void validate(BattleContext context, Command command) {
        super.validate(context, command);
        if (command instanceof TickCommand) {
            firstAliveInFaction(context, "player");
            firstAliveInFaction(context, "enemy");
        }
    }

    static Entity firstAliveInFaction(BattleContext context, String faction) {
        return context.state().entities().stream()
                .filter(entity -> entity.find(FactionComponent.class)
                        .map(component -> component.faction().equals(faction))
                        .orElse(false))
                .filter(entity -> entity.find(HealthComponent.class)
                        .map(HealthComponent::alive)
                        .orElse(false))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No alive unit in faction " + faction));
    }
}
